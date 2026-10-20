/**
 * app-shell-login（FE-02／QA-03）驗收測試共用工具（qa 撰寫，worker 不可改）。
 * 後端一律依 docs/contracts/auth.md §1～§3 mock；UI 文案與結構依 docs/ui/journey-platform.md 與設計稿 index.html。
 *
 * 對 worker 的假設：
 *   - 進入點為 `web/src/App.tsx` 具名匯出 `App`（同既有測試）；請求走全域 fetch、相對路徑 /api/v1/...
 *   - 本組測試不假設新頁面的網址（受眾、修改密碼），一律從導覽或帳號區的連結點進去。
 *   - 未登入時網址為 /login（沿用 v0.1.0），登入後回 ?next= 的原頁。
 *   - 預設頁面 `/` 與其 GET API 不在本單範圍：mock 對 /auth/me 以外的 GET 預設回 200 `{ items: [], next_cursor: null }`。
 */
import { StrictMode } from 'react'
import { cleanup, render } from '@testing-library/react'
import { vi } from 'vitest'
import { App } from '../../../src/App'

export type Role = 'VIEWER' | 'EDITOR' | 'ADMIN'
export interface CurrentUser { user_id: string; email: string; display_name: string; role: Role }
export const ROLE_LABEL: Record<Role, string> = { VIEWER: '檢視者', EDITOR: '編輯者', ADMIN: '管理員' }
export const AMY: CurrentUser = {
  user_id: '01JD0USER0000000000000001', email: 'amy.chen@example.com', display_name: '陳小美', role: 'EDITOR',
}
export const user = (over: Partial<CurrentUser> = {}): CurrentUser => ({ ...AMY, ...over })

export interface Call {
  method: string
  path: string
  search: string
  headers: Record<string, string>
  body: unknown
}
type Handler = (call: Call) => Response | Promise<Response>

export const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  })
export const problem = (status: number, code: string, headers: Record<string, string> = {}) =>
  json({ type: 'about:blank', title: 'x', status, code, detail: 'd', request_id: 'REQ-' + code }, status, headers)
export const noContent = () => new Response(null, { status: 204 })

export function setCookie(name: string, value: string) {
  document.cookie = `${name}=${value}; path=/`
}
export function clearCookies() {
  for (const c of document.cookie.split(';')) {
    const name = c.split('=')[0].trim()
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
  }
}
export const cookie = (name: string) =>
  document.cookie.split(';').map((c) => c.trim()).find((c) => c.startsWith(name + '='))?.slice(name.length + 1)

function headersOf(init?: RequestInit): Record<string, string> {
  const out: Record<string, string> = {}
  const h = init?.headers
  if (!h) return out
  if (h instanceof Headers) h.forEach((v, k) => (out[k.toLowerCase()] = v))
  else if (Array.isArray(h)) for (const [k, v] of h) out[k.toLowerCase()] = v
  else for (const [k, v] of Object.entries(h)) out[k.toLowerCase()] = String(v)
  return out
}

export interface Api {
  calls: Call[]
  /** 目前 session 的使用者；null = 未登入 */
  me: CurrentUser | null
  /** 登入成功時要回給前端的使用者 */
  loginAs: CurrentUser
  /** /auth/me 被呼叫的次數 */
  meCount: () => number
  count: (method: string, path: string) => Call[]
}

/**
 * 依 auth.md 的預設後端：
 *  - GET /auth/me：已登入 200 CurrentUser，並換新 cookie XSRF-TOKEN（§1.1）；未登入 401 UNAUTHENTICATED
 *  - POST /auth/login：204，建立 session、換新 XSRF-TOKEN；
 *  - POST /auth/logout：204，清 session
 *  - POST /auth/password：標頭 X-XSRF-TOKEN 不等於 cookie → 403 CSRF_INVALID；否則 204
 *  - 其他 GET：已登入 200 空清單；未登入 401
 */
export function mockApi(opts: { me?: CurrentUser | null; routes?: Record<string, Handler> } = {}): Api {
  const api: Api = {
    calls: [],
    me: opts.me === undefined ? null : opts.me,
    loginAs: AMY,
    meCount: () => api.calls.filter((c) => c.method === 'GET' && c.path === '/api/v1/auth/me').length,
    count: (method, path) => api.calls.filter((c) => c.method === method && c.path === path),
  }
  let seq = 0
  const fresh = () => setCookie('XSRF-TOKEN', `tok-${++seq}`)
  if (api.me) fresh()
  const routes: Record<string, Handler> = {
    'GET /api/v1/auth/me': () => {
      if (!api.me) return problem(401, 'UNAUTHENTICATED')
      fresh()
      return json(api.me)
    },
    'POST /api/v1/auth/login': () => {
      api.me = api.loginAs
      fresh()
      return noContent()
    },
    'POST /api/v1/auth/logout': () => {
      api.me = null
      return noContent()
    },
    'POST /api/v1/auth/password': (c) =>
      c.headers['x-xsrf-token'] && c.headers['x-xsrf-token'] === cookie('XSRF-TOKEN')
        ? noContent()
        : problem(403, 'CSRF_INVALID'),
    ...opts.routes,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      const url = new URL(raw, 'http://localhost')
      const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
      let body: unknown = undefined
      if (typeof init?.body === 'string') {
        try { body = JSON.parse(init.body) } catch { body = init.body }
      }
      const call: Call = { method, path: url.pathname, search: url.search, headers: headersOf(init), body }
      api.calls.push(call)
      const h = routes[`${method} ${url.pathname}`]
      if (h) return h(call)
      if (method === 'GET' && url.pathname.startsWith('/api/v1/')) {
        return api.me ? json({ items: [], next_cursor: null }) : problem(401, 'UNAUTHENTICATED')
      }
      return problem(404, 'NOT_FOUND')
    }),
  )
  return api
}

export function start(path = '/') {
  window.history.pushState({}, '', path)
  return render(<StrictMode><App /></StrictMode>)
}

export function resetEnv() {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  window.sessionStorage.clear()
  clearCookies()
}
export function teardown() {
  cleanup()
  document.body.innerHTML = ''
}
export const text = () => document.body.textContent ?? ''

/** 目前仍在畫面上（未 aria-hidden）的 role=alert 文字；排除 aria-hidden 子樹 */
export function announced(el: Element): string {
  const clone = el.cloneNode(true) as Element
  clone.querySelectorAll('[aria-hidden="true"]').forEach((n) => n.remove())
  return (clone.textContent ?? '').replace(/\s+/g, ' ').trim()
}

/** 外框帳號區的「登出」（桌面側欄與窄螢幕選單各有一份，jsdom 不套 CSS，兩份都在；取第一個可點的） */
export function logoutControl(): HTMLElement {
  const els = [...document.querySelectorAll<HTMLElement>('button, a, [role="button"], [role="link"]')].filter(
    (e) => e.textContent?.trim() === '登出',
  )
  if (!els.length) throw new Error('找不到「登出」（應在外框帳號區）')
  return els[0]
}
