import { defineStore } from 'pinia'
import { api } from '../api/http'
import type { TokenPair, UserView } from '../types'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: localStorage.getItem('access_token') as string | null,
    user: JSON.parse(localStorage.getItem('user') || 'null') as UserView | null,
  }),
  getters: {
    authenticated: (state) => Boolean(state.accessToken),
    isAdmin: (state) => Boolean(state.user?.roles?.includes('ADMIN')),
  },
  actions: {
    remember(pair: TokenPair) {
      localStorage.setItem('access_token', pair.accessToken)
      localStorage.setItem('refresh_token', pair.refreshToken)
      localStorage.setItem('user', JSON.stringify(pair.user))
      this.accessToken = pair.accessToken
      this.user = pair.user
    },
    async login(login: string, password: string) { this.remember(await api<TokenPair>({ method: 'POST', url: '/auth/login', data: { login, password, deviceId: 'web' } })) },
    async register(username: string, email: string, password: string) { this.remember(await api<TokenPair>({ method: 'POST', url: '/auth/register', data: { username, email, password, deviceId: 'web' } })) },
    async hydrate() { if (this.authenticated) { this.user = await api<UserView>({ url: '/users/me' }); localStorage.setItem('user', JSON.stringify(this.user)) } },
    async logout() { try { await api({ method: 'POST', url: '/auth/logout', data: { refreshToken: localStorage.getItem('refresh_token') } }) } finally { localStorage.clear(); this.accessToken = null; this.user = null } },
  },
})
