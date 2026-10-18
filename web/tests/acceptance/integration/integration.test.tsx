// @vitest-environment jsdom
/**
 * INT-01 驗收測試（qa 撰寫，worker 不可改）。以攔截全域 fetch 模擬「真實後端」，依契約 §1.2、§4、§5 回應。
 * 真實起後端的端到端驗證由 B 階段與 uat 負責，不在此。
 *
 * 對 worker 的假設：
 *   - 進入點：`web/src/main.tsx`（掛載到 #root）與 `web/src/App.tsx` 具名匯出 `App`（與 FE-01 相同）。
 *   - main.tsx 不再載入 mock，也不看 VITE_API_MOCK；`web/src/mock/` 目錄移除。
 *   - 所有 fetch 第二參數帶 `credentials: 'same-origin'`（或 'include'），路徑為相對的 `/api/v1/...`。
 *   - 403：ErrorBox 文案含「沒有權限」，且該 role=alert 區塊內沒有任何按鈕（無「重試」）。
 *   - 其他錯誤（5xx 等）顯示 problem+json 的 request_id（或回應標頭 X-Request-Id）。
 *   - 頁首顯示 /me 的 username（列表與詳情都有）。
 */
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../../src/App'

const EID = '01JCXYZENROLL000000000001'
const PID = '01JCXYZPROFILE00000000001'

type Handler = (url: URL, init?: RequestInit) => Response | Promise<Response>
let calls: { method: string; url: URL; init?: RequestInit; body?: string; rawInput: unknown }[] = []

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  })
const problem = (status: number, code: string, request_id = 'REQ-' + code) =>
  json({ type: 'about:blank', title: 'x', status, code, detail: 'd', request_id }, status, { 'X-Request-Id': request_id })
const empty = () => new Response(null, { status: 204 })

const summary = () => ({
  enrollment_id: EID, journey_id: 'j1', profile_id: PID, current_node_id: 'wait_1', current_node_type: 'WaitDelayNode',
  journey_version: 2, journey_name: '棄單挽回', current_node_name: '等 3 天', status: 'WAITING',
  enrolled_at: '2026-10-18T02:00:00Z', updated_at: '2026-10-18T02:00:05Z', terminated_at: null,
})
const delivery = (i: number) => ({
  idempotency_key: 'idem-' + i, node_id: 'send_1', trace_seq: 3, node_name: '發送', template_id: 'tpl_' + i,
  channel_id: 'ch_mock_1', channel_type: 'MOCK', message_type: null, status: 'SENT', skip_reason: null,
  attempted_at: '2026-10-13T01:12:00Z', delivery_id: 'd' + i,
})
const detail = (over: Record<string, unknown> = {}) => ({
  ...summary(), termination_reason: null,
  wake_condition: { type: 'TIMER', wake_at: '2026-10-21T02:00:00Z', event_name: null, signal_name: null, timeout_at: null },
  variables: {},
  trigger_snapshot: { event_id: 'ev1', event_name: 'cart_item_added', source_type: 'CUSTOMER_BEHAVIOR', occurred_at: '2026-10-18T01:59:41Z', properties: {}, profile_snapshot: {} },
  trace: [
    { seq: 1, node_id: 'src_1', node_type: 'EventSourceNode', node_name: '加入購物車', entered_at: '2026-10-18T02:00:00Z', exited_at: '2026-10-18T02:00:00Z', out_port: 'next', decision_reason: '收到事件。', evaluated: {} },
  ],
  delivery_history: [], delivery_history_next_cursor: null, as_of: '2026-10-18T02:00:05Z', masked_paths: [], ...over,
})

function install(extra: Record<string, Handler> = {}, authed = true) {
  const routes: Record<string, Handler> = {
    'GET /api/v1/auth/me': () => (authed ? json({ username: 'support01' }) : problem(401, 'UNAUTHENTICATED')),
    'POST /api/v1/auth/logout': () => empty(),
    ...extra,
  }
  calls = []
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, 'http://localhost')
    const method = (init?.method ?? 'GET').toUpperCase()
    calls.push({ method, url, init, rawInput: input, body: typeof init?.body === 'string' ? init.body : undefined })
    const h = routes[`${method} ${url.pathname}`]
    return h ? h(url, init) : problem(404, 'NOT_FOUND')
  })
  vi.stubGlobal('fetch', fn)
  return fn
}
const start = (path: string) => {
  window.history.pushState({}, '', path)
  return render(<App />)
}
const lastCall = (m: string, p: string) => [...calls].reverse().find((c) => c.method === m && c.url.pathname === p)
const h1 = () => screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
const withMore = () => detail({ delivery_history: [delivery(1)], delivery_history_next_cursor: 'DC1' })

beforeEach(() => {
  vi.unstubAllGlobals()
})
afterEach(() => {
  cleanup()
  document.body.innerHTML = ''
})

describe('不載入 mock，打真實端點', () => {
  it('mock 程式已移除（src/mock 不存在、main.tsx 不提 mock）', () => {
    expect(existsSync(resolve(__dirname, '../../../src/mock'))).toBe(false)
    expect(readFileSync(resolve(__dirname, '../../../src/main.tsx'), 'utf8')).not.toMatch(/mock/i)
  })

  it('每個請求都是相對的 /api/v1/ 路徑並帶 credentials（same-origin 或 include）', async () => {
    install({
      'GET /api/v1/enrollments': () => json({ items: [summary()], next_cursor: null }),
      [`GET /api/v1/enrollments/${EID}`]: () => json(withMore()),
      [`GET /api/v1/enrollments/${EID}/deliveries`]: () => json({ items: [delivery(2)], next_cursor: null }),
    })
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    await user.click(within(await screen.findByRole('table')).getAllByRole('link')[0])
    await user.click(await screen.findByRole('button', { name: '載入更早紀錄' }))
    await waitFor(() => expect(screen.getByRole('table').textContent).toContain('tpl_2'))
    await user.click(screen.getByRole('button', { name: '登出' }))
    await screen.findByLabelText('帳號')
    const paths = new Set(calls.map((c) => c.url.pathname))
    for (const p of [
      '/api/v1/auth/me',
      '/api/v1/enrollments',
      `/api/v1/enrollments/${EID}`,
      `/api/v1/enrollments/${EID}/deliveries`,
      '/api/v1/auth/logout',
    ])
      expect(paths.has(p)).toBe(true)
    for (const c of calls) {
      expect(typeof c.rawInput).toBe('string')
      expect(c.rawInput as string).toMatch(/^\/api\/v1\//)
      expect(['same-origin', 'include']).toContain(c.init?.credentials)
    }
  })

  it('登入請求也帶 credentials，內容為 JSON', async () => {
    install({ 'POST /api/v1/auth/login': () => empty() }, false)
    const user = userEvent.setup()
    start('/login')
    await user.type(await screen.findByLabelText('帳號'), 'u')
    await user.type(screen.getByLabelText('密碼'), 'p')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(lastCall('POST', '/api/v1/auth/login')).toBeTruthy())
    const c = lastCall('POST', '/api/v1/auth/login')!
    expect(['same-origin', 'include']).toContain(c.init?.credentials)
    expect(new Headers(c.init?.headers).get('Content-Type')).toBe('application/json')
  })
})

describe('登入、401、登出、頁首', () => {
  it('頁首顯示 /me 的 username（列表與詳情）', async () => {
    install({
      'GET /api/v1/auth/me': () => json({ username: 'agent_chen' }),
      [`GET /api/v1/enrollments/${EID}`]: () => json(detail()),
    })
    start('/')
    await screen.findByText('agent_chen')
    cleanup()
    start(`/enrollments/${EID}`)
    await h1()
    expect(screen.getByText('agent_chen')).toBeTruthy()
  })

  it('列表查詢中 session 過期（401）導回 /login，登入後回原頁含 ?q=', async () => {
    let loggedIn = true
    install({
      'GET /api/v1/auth/me': () => (loggedIn ? json({ username: 'support01' }) : problem(401, 'UNAUTHENTICATED')),
      'GET /api/v1/enrollments': () =>
        loggedIn ? json({ items: [summary()], next_cursor: null }) : problem(401, 'UNAUTHENTICATED'),
      'POST /api/v1/auth/login': () => {
        loggedIn = true
        return empty()
      },
    })
    const user = userEvent.setup()
    start('/')
    await user.type(await screen.findByLabelText('客戶 ID 或 Enrollment ID'), `${PID}{Enter}`)
    await screen.findByRole('table')
    loggedIn = false // 後端重啟或逾時
    await user.click(screen.getByRole('button', { name: '查詢' }))
    await user.type(await screen.findByLabelText('帳號'), 'support01')
    expect(window.location.pathname).toBe('/login')
    await user.type(screen.getByLabelText('密碼'), 'pw')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await screen.findByRole('table')
    expect(window.location.pathname).toBe('/')
    expect(window.location.search).toContain(`q=${PID}`)
  })

  it('詳情頁載入更早紀錄時 401：導回登入，登入後回該詳情頁', async () => {
    let loggedIn = true
    install({
      'GET /api/v1/auth/me': () => (loggedIn ? json({ username: 'support01' }) : problem(401, 'UNAUTHENTICATED')),
      [`GET /api/v1/enrollments/${EID}`]: () => (loggedIn ? json(withMore()) : problem(401, 'UNAUTHENTICATED')),
      [`GET /api/v1/enrollments/${EID}/deliveries`]: () => problem(401, 'UNAUTHENTICATED'),
      'POST /api/v1/auth/login': () => {
        loggedIn = true
        return empty()
      },
    })
    const user = userEvent.setup()
    start(`/enrollments/${EID}`)
    const more = await screen.findByRole('button', { name: '載入更早紀錄' })
    loggedIn = false
    await user.click(more)
    await user.type(await screen.findByLabelText('帳號'), 'support01')
    await user.type(screen.getByLabelText('密碼'), 'pw')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await h1()
    expect(window.location.pathname).toBe(`/enrollments/${EID}`)
  })

  it('登出後 session 失效：回 /login，重新進站仍在登入頁；再登入回首頁', async () => {
    let loggedIn = true
    install({
      'GET /api/v1/auth/me': () => (loggedIn ? json({ username: 'support01' }) : problem(401, 'UNAUTHENTICATED')),
      'POST /api/v1/auth/logout': () => {
        loggedIn = false
        return empty()
      },
      'POST /api/v1/auth/login': () => {
        loggedIn = true
        return empty()
      },
    })
    const user = userEvent.setup()
    start('/')
    await screen.findByText('support01')
    await user.click(screen.getByRole('button', { name: '登出' }))
    await screen.findByLabelText('帳號')
    expect(window.location.pathname).toBe('/login')
    expect(screen.queryByText('support01')).toBeNull()
    cleanup()
    start('/')
    await screen.findByLabelText('帳號')
    expect(window.location.pathname).toBe('/login')
    await user.type(screen.getByLabelText('帳號'), 'support01')
    await user.type(screen.getByLabelText('密碼'), 'pw')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await screen.findByLabelText('客戶 ID 或 Enrollment ID')
    expect(window.location.pathname).toBe('/')
  })
})

describe('403 與其他錯誤', () => {
  const noRetry = (alert: HTMLElement) => {
    expect(alert.textContent).toContain('沒有權限')
    expect(within(alert).queryAllByRole('button')).toHaveLength(0)
    expect(alert.textContent).not.toContain('重試')
    expect(alert.textContent).not.toContain('伺服器暫時無法回應')
  }

  it('列表 403：顯示「沒有權限」，無重試按鈕', async () => {
    install({ 'GET /api/v1/enrollments': () => problem(403, 'FORBIDDEN', 'REQ-403') })
    start(`/?q=${PID}`)
    noRetry(await screen.findByRole('alert'))
  })

  it('詳情 403：顯示「沒有權限」，無重試按鈕', async () => {
    install({ [`GET /api/v1/enrollments/${EID}`]: () => problem(403, 'FORBIDDEN') })
    start(`/enrollments/${EID}`)
    noRetry(await screen.findByRole('alert'))
  })

  it('載入更早紀錄 403：顯示「沒有權限」，無重試按鈕', async () => {
    install({
      [`GET /api/v1/enrollments/${EID}`]: () => json(withMore()),
      [`GET /api/v1/enrollments/${EID}/deliveries`]: () => problem(403, 'FORBIDDEN'),
    })
    const user = userEvent.setup()
    start(`/enrollments/${EID}`)
    await user.click(await screen.findByRole('button', { name: '載入更早紀錄' }))
    await waitFor(() => expect(screen.getAllByRole('alert').some((a) => a.textContent?.includes('沒有權限'))).toBe(true))
    noRetry(screen.getAllByRole('alert').find((a) => a.textContent?.includes('沒有權限')) as HTMLElement)
  })

  it('403 不導回登入', async () => {
    install({ 'GET /api/v1/enrollments': () => problem(403, 'FORBIDDEN') })
    start(`/?q=${PID}`)
    await screen.findByRole('alert')
    expect(window.location.pathname).toBe('/')
  })

  it('500 顯示 request_id 與重試；非 403 不顯示「沒有權限」', async () => {
    install({ [`GET /api/v1/enrollments/${EID}`]: () => problem(500, 'INTERNAL_ERROR', 'REQ-DETAIL-500') })
    start(`/enrollments/${EID}`)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('REQ-DETAIL-500')
    expect(alert.textContent).not.toContain('沒有權限')
    expect(within(alert).getByRole('button', { name: '重試' })).toBeTruthy()
  })

  it('載入更早紀錄 503：顯示 request_id', async () => {
    install({
      [`GET /api/v1/enrollments/${EID}`]: () => json(withMore()),
      [`GET /api/v1/enrollments/${EID}/deliveries`]: () => problem(503, 'SERVICE_UNAVAILABLE', 'REQ-DLV-503'),
    })
    const user = userEvent.setup()
    start(`/enrollments/${EID}`)
    await user.click(await screen.findByRole('button', { name: '載入更早紀錄' }))
    await waitFor(() => expect(document.body.textContent).toContain('REQ-DLV-503'))
  })

  it('錯誤只有標頭 X-Request-Id（body 非 JSON）也顯示 request_id', async () => {
    install({
      'GET /api/v1/enrollments': () => new Response('bad gateway', { status: 502, headers: { 'X-Request-Id': 'REQ-HDR-502' } }),
    })
    start(`/?q=${PID}`)
    expect((await screen.findByRole('alert')).textContent).toContain('REQ-HDR-502')
  })

  it('/me 回 500：顯示 request_id，不導向登入頁', async () => {
    install({ 'GET /api/v1/auth/me': () => problem(500, 'INTERNAL_ERROR', 'REQ-ME-500') })
    start('/')
    expect((await screen.findByRole('alert')).textContent).toContain('REQ-ME-500')
    expect(window.location.pathname).not.toBe('/login')
  })
})
