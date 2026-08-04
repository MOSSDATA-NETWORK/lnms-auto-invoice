import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import type { ProblemDetails } from './types'

export const api = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: { Accept: 'application/json' },
})

api.interceptors.request.use((config) => {
  config.headers.set('X-Request-Id', crypto.randomUUID())
  return config
})

api.interceptors.response.use(undefined, (error: unknown) => {
  if (axios.isAxiosError<ProblemDetails>(error)) {
    const problem = error.response?.data
    if (
      problem?.code === 'PASSWORD_CHANGE_REQUIRED' &&
      typeof window !== 'undefined' &&
      window.location.pathname !== '/sign-in'
    ) {
      const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`
      window.location.assign(
        `/sign-in?redirect=${encodeURIComponent(returnTo)}`
      )
    }
  }
  return Promise.reject(error)
})

export function problemFrom(error: unknown): ProblemDetails {
  if (axios.isAxiosError(error)) {
    return (
      (error as AxiosError<ProblemDetails>).response?.data ?? {
        title: '网络请求失败',
        detail: error.message,
      }
    )
  }
  return { title: '未知错误', detail: String(error) }
}

export function normalizeGeneratedApiPath(url?: string): string | undefined {
  if (!url?.startsWith('/api/v1')) return url

  const normalized = url.replace(/^\/api\/v1(?=\/|$)/, '')
  return normalized || '/'
}

export async function apiMutator<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await api.request<T>({
    ...config,
    url: normalizeGeneratedApiPath(config.url),
  })
  return response.data
}

export async function ensureCsrf(): Promise<void> {
  await api.get('/auth/csrf')
}

export function idempotencyKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}
