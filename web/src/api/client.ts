// 所有呼叫走相對路徑 /api/v1（dev server 轉給後端，見契約 §1）。
// session 的寫入請求（非 GET，登入／登出除外）帶 X-XSRF-TOKEN（docs/contracts/auth.md §1.1）。
import type { CurrentUser, Delivery, EnrollmentDetail, EnrollmentSummary, Page } from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly requestId: string | null
  /** 429 的 Retry-After（秒）；其他情況為 null */
  readonly retryAfter: number | null
  constructor(status: number, code: string | null, requestId: string | null, message?: string, retryAfter: number | null = null) {
    super(message ?? `HTTP ${status}`)
    this.status = status
    this.code = code
    this.requestId = requestId
    this.retryAfter = retryAfter
  }
}

// 任何 API 回 401 UNAUTHENTICATED → 由 App 導回登入（契約 §3）
let onUnauthorized: (() => void) | null = null
let unauthorizedTimer: number | null = null
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn
}
/** 同時到達的多個 401 合併成一次導向（延到下一個工作週期處理） */
function notifyUnauthorized() {
  if (unauthorizedTimer !== null) return
  unauthorizedTimer = window.setTimeout(() => {
    unauthorizedTimer = null
    onUnauthorized?.()
  }, 0)
}

// 讀取資料（GET）回 403 FORBIDDEN → 由 App 顯示整頁無權限
let onForbidden: (() => void) | null = null
export function setForbiddenHandler(fn: (() => void) | null) {
  onForbidden = fn
}

interface Options {
  body?: unknown
  /** false = 401 不導回登入（登入、/me 自行處理） */
  redirectOn401?: boolean
  /** false = 不帶 X-XSRF-TOKEN（登入、登出） */
  csrf?: boolean
  /** false = 403 FORBIDDEN 不切整頁無權限（區塊內自行顯示） */
  pageOn403?: boolean
}

/** 每次送出前讀 cookie 當下的值，不快取（契約 §1.1） */
function xsrfToken(): string | null {
  for (const part of document.cookie.split(';')) {
    const c = part.trim()
    if (c.startsWith('XSRF-TOKEN=')) return decodeURIComponent(c.slice('XSRF-TOKEN='.length))
  }
  return null
}

async function toError(res: Response): Promise<ApiError> {
  const body: { code?: string; request_id?: string } | null = await res.json().catch(() => null)
  const ra = Number.parseInt(res.headers.get('Retry-After') ?? '', 10)
  return new ApiError(
    res.status,
    body?.code ?? null,
    body?.request_id ?? res.headers.get('X-Request-Id'),
    undefined,
    Number.isFinite(ra) && ra >= 0 ? ra : null,
  )
}

async function send(method: string, path: string, opts: Options): Promise<Response> {
  const init: RequestInit = { method, credentials: 'same-origin' }
  const headers: Record<string, string> = {}
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(opts.body)
  }
  if (method !== 'GET' && opts.csrf !== false) {
    const token = xsrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  if (Object.keys(headers).length) init.headers = headers
  try {
    return await fetch(path, init)
  } catch {
    throw new ApiError(0, null, null, '連不上伺服器')
  }
}

async function request<T>(method: string, path: string, opts: Options = {}): Promise<T> {
  let res = await send(method, path, opts)
  let err: ApiError | null = res.ok ? null : await toError(res)
  // CSRF_INVALID：重取 /auth/me 換新 cookie 值後，原請求重試一次（仍失敗就交給畫面）
  if (err && method !== 'GET' && opts.csrf !== false && err.status === 403 && err.code === 'CSRF_INVALID') {
    await getMe()
    res = await send(method, path, opts)
    err = res.ok ? null : await toError(res)
  }
  if (err) {
    if (err.status === 401 && opts.redirectOn401 !== false && err.code !== 'INVALID_CREDENTIALS') notifyUnauthorized()
    if (err.status === 403 && err.code === 'FORBIDDEN' && method === 'GET' && opts.pageOn403 !== false) onForbidden?.()
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

export const getMe = () => request<CurrentUser>('GET', '/api/v1/auth/me', { redirectOn401: false })
export const login = (email: string, password: string) =>
  request<void>('POST', '/api/v1/auth/login', { body: { email, password }, redirectOn401: false, csrf: false })
export const logout = () => request<void>('POST', '/api/v1/auth/logout', { redirectOn401: false, csrf: false })
export const changePassword = (current_password: string, new_password: string) =>
  request<void>('POST', '/api/v1/auth/password', { body: { current_password, new_password } })

export const listEnrollments = (p: { q: string; status?: string; cursor?: string }) =>
  request<Page<EnrollmentSummary>>('GET', `/api/v1/enrollments${qs({ q: p.q, status: p.status, cursor: p.cursor })}`)
export const getEnrollment = (id: string) => request<EnrollmentDetail>('GET', `/api/v1/enrollments/${enc(id)}`)
export const listDeliveries = (id: string, cursor: string) =>
  request<Page<Delivery>>('GET', `/api/v1/enrollments/${enc(id)}/deliveries${qs({ cursor })}`, { pageOn403: false })
