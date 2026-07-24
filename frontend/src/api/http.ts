import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiEnvelope, TokenPair } from '../types'

export const apiBaseURL = import.meta.env.VITE_API_BASE_URL || '/api/v1'
const baseURL = apiBaseURL
export const http = axios.create({ baseURL, timeout: 30000 })
let refreshing: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  const refreshToken = localStorage.getItem('refresh_token')
  if (!refreshToken) throw new Error('登录已过期')
  refreshing ||= axios.post<ApiEnvelope<TokenPair>>(`${baseURL}/auth/refresh`, { refreshToken, deviceId: 'web' })
    .then(({ data }) => {
      localStorage.setItem('access_token', data.data.accessToken)
      localStorage.setItem('refresh_token', data.data.refreshToken)
      return data.data.accessToken
    }).finally(() => { refreshing = null })
  return refreshing
}

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  config.headers['X-Request-Id'] ||= crypto.randomUUID()
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<{ message?: string; error?: { message?: string }; requestId?: string }>) => {
    const original = error.config as (AxiosRequestConfig & { _retried?: boolean }) | undefined
    if (error.response?.status === 401 && original && !original._retried && !String(original.url).includes('/auth/refresh')) {
      original._retried = true
      if (localStorage.getItem('refresh_token')) {
        try {
          const token = await refreshAccessToken()
          original.headers = { ...original.headers, Authorization: `Bearer ${token}` }
          return http(original)
        } catch { localStorage.clear(); location.assign('/login') }
      }
    }
    const body = error.response?.data
    const message = body?.message || body?.error?.message || error.message || '请求失败'
    if (error.response?.status !== 401) ElMessage.error(`${message}${body?.requestId ? ` · ${body.requestId}` : ''}`)
    return Promise.reject(error)
  },
)

export async function api<T>(config: AxiosRequestConfig): Promise<T> {
  const { data } = await http.request<ApiEnvelope<T>>(config)
  return data.data
}

export const idempotencyKey = () => crypto.randomUUID()

export type SseEvent = { event:string; data:any }

export class AuthenticationExpiredError extends Error {
  constructor() { super('登录已过期'); this.name = 'AuthenticationExpiredError' }
}

export async function postSse(path:string, body:unknown, onEvent:(event:SseEvent)=>void,
                              signal?:AbortSignal):Promise<void> {
  const url = `${baseURL.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
  const request = async (token:string|null) => fetch(url, {
    method:'POST', signal,
    headers:{
      'Accept':'text/event-stream', 'Content-Type':'application/json', 'X-Request-Id':crypto.randomUUID(),
      ...(token ? { Authorization:`Bearer ${token}` } : {}),
    },
    body:JSON.stringify(body),
  })

  let response = await request(localStorage.getItem('access_token'))
  if (response.status === 401 && localStorage.getItem('refresh_token')) {
    try { response = await request(await refreshAccessToken()) }
    catch { localStorage.clear(); location.assign('/login'); throw new AuthenticationExpiredError() }
  }
  if (response.status === 401) {
    localStorage.clear()
    location.assign('/login')
    throw new AuthenticationExpiredError()
  }
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const payload = await response.json()
      message = payload?.message || payload?.error?.message || message
    } catch { /* 非 JSON 错误响应 */ }
    throw new Error(message)
  }
  const contentType = response.headers.get('content-type') || ''
  // Rolling upgrades may temporarily pair the new frontend with the previous synchronous backend.
  // Consume its successful envelope without reporting a false authentication/network error.
  if (contentType.includes('application/json')) {
    const payload = await response.json()
    if (payload?.success === false) throw new Error(payload?.message || payload?.error?.message || '请求失败')
    onEvent({ event:'stream.compatibility', data:{ mode:'SYNC_JSON' } })
    onEvent({ event:'message.completed', data:payload?.data })
    return
  }
  if (!response.body || !contentType.includes('text/event-stream')) {
    throw new Error('服务器未返回流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const consume = (block:string) => {
    let event = 'message'
    const data:string[] = []
    for (const rawLine of block.split(/\r?\n/)) {
      const line = rawLine.endsWith('\r') ? rawLine.slice(0,-1) : rawLine
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (!data.length) return
    const joined = data.join('\n')
    let parsed:any = joined
    try { parsed = JSON.parse(joined) } catch { /* 允许纯文本 SSE */ }
    onEvent({ event, data:parsed })
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream:true })
    let match = /\r?\n\r?\n/.exec(buffer)
    while (match?.index !== undefined) {
      consume(buffer.slice(0, match.index))
      buffer = buffer.slice(match.index + match[0].length)
      match = /\r?\n\r?\n/.exec(buffer)
    }
  }
  buffer += decoder.decode()
  if (buffer.trim()) consume(buffer)
}
