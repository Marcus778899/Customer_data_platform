// 所有呼叫走相對路徑 /api/v1（dev server 轉給後端，見契約 §1）。唯讀：只有登入／登出是 POST。
import type { Delivery, EnrollmentDetail, EnrollmentSummary, Page } from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly requestId: string | null
  constructor(status: number, code: string | null, requestId: string | null, message?: string) {
    super(message ?? `HTTP ${status}`)
    this.status = status
    this.code = code
    this.requestId = requestId
  }
}

// 任何 API 回 401 → 由 App 導回登入（契約 §1.2）
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn
}

interface Options {
  body?: unknown
  /** false = 401 不導回登入（登入、/me 自行處理） */
  redirectOn401?: boolean
}

async function request<T>(method: string, path: string, opts: Options = {}): Promise<T> {
  const init: RequestInit = { method, credentials: 'same-origin' }
  if (opts.body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(opts.body)
  }
  let res: Response
  try {
    res = await fetch(path, init)
  } catch {
    throw new ApiError(0, null, null, '連不上伺服器')
  }
  if (!res.ok) {
    const body: { code?: string; request_id?: string } | null = await res.json().catch(() => null)
    const err = new ApiError(res.status, body?.code ?? null, body?.request_id ?? res.headers.get('X-Request-Id'))
    if (res.status === 401 && opts.redirectOn401 !== false) onUnauthorized?.()
    throw err
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

const enc = encodeURIComponent
function qs(params: Record<string, string | number | undefined>) {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${enc(String(v))}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

export const getMe = () => request<{ username: string }>('GET', '/api/v1/auth/me', { redirectOn401: false })
export const login = (username: string, password: string) =>
  request<void>('POST', '/api/v1/auth/login', { body: { username, password }, redirectOn401: false })
export const logout = () => request<void>('POST', '/api/v1/auth/logout', { redirectOn401: false })

export const listEnrollments = (p: { q: string; status?: string; cursor?: string }) =>
  request<Page<EnrollmentSummary>>('GET', `/api/v1/enrollments${qs({ q: p.q, status: p.status, cursor: p.cursor })}`)
export const getEnrollment = (id: string) => request<EnrollmentDetail>('GET', `/api/v1/enrollments/${enc(id)}`)
export const listDeliveries = (id: string, cursor: string) =>
  request<Page<Delivery>>('GET', `/api/v1/enrollments/${enc(id)}/deliveries${qs({ cursor })}`)
