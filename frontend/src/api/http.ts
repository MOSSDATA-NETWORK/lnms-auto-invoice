import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import type { ProblemDetails } from './types'

export const api = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: { Accept: 'application/json' },
})

// crypto.randomUUID requires a secure context (HTTPS or localhost); plain-HTTP
// deployments still have crypto.getRandomValues.
function randomId(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0'))
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10).join('')}`
}

api.interceptors.request.use((config) => {
  config.headers.set('X-Request-Id', randomId())
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
  return `${prefix}-${randomId()}`
}
