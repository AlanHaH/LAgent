import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiEnvelope, TokenPair } from '../types'

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api/v1'
export const http = axios.create({ baseURL, timeout: 30000 })
let refreshing: Promise<string> | null = null

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
      const refreshToken = localStorage.getItem('refresh_token')
      if (refreshToken) {
        refreshing ||= axios.post<ApiEnvelope<TokenPair>>(`${baseURL}/auth/refresh`, { refreshToken, deviceId: 'web' })
          .then(({ data }) => {
            localStorage.setItem('access_token', data.data.accessToken)
            localStorage.setItem('refresh_token', data.data.refreshToken)
            return data.data.accessToken
          }).finally(() => { refreshing = null })
        try {
          const token = await refreshing
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
