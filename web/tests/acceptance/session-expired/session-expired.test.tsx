// @vitest-environment jsdom
/**
 * FIX-06 驗收測試（qa 撰寫，worker 不可改）。攔截全域 fetch，寫法同 integration 測試。
 * 文案依 docs/ui/enrollment-trace.md：逾時「登入已逾時，請重新登入」；401「帳號或密碼不正確」。
 *
 * 對 worker 的假設：
 *   - 進入點為 `web/src/App.tsx` 具名匯出 `App`；請求路徑與契約同 integration 測試。
 *   - 提示以文字呈現於頁面（不限 role），斷言用 document.body.textContent。
 *   - 「已登入」以 /me 曾成功為準；提示判斷不得只靠網址參數（直接開 /login?next=… 不應顯示）。
 */
import { StrictMode } from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../../src/App'

const EXPIRED = '登入已逾時，請重新登入'
const BAD_CRED = '帳號或密碼不正確'
const PID = '01JCXYZPROFILE00000000001'
const EID = '01JCXYZENROLL000000000001'

type Handler = (url: URL, init?: RequestInit) => Response | Promise<Response>
type State = { me: boolean; list: boolean; loginOk: boolean }

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
const problem = (status: number, code: string) =>
  json({ type: 'about:blank', title: 'x', status, code, detail: 'd', request_id: 'REQ-' + code }, status)
const empty = () => new Response(null, { status: 204 })
const summary = () => ({
  enrollment_id: EID, journey_id: 'j1', profile_id: PID, current_node_id: 'wait_1', current_node_type: 'WaitDelayNode',
  journey_version: 2, journey_name: '棄單挽回', current_node_name: '等 3 天', status: 'WAITING',
  enrolled_at: '2026-10-18T02:00:00Z', updated_at: '2026-10-18T02:00:05Z', terminated_at: null,
})
const detail = () => ({
  ...summary(), termination_reason: null,
  wake_condition: { type: 'TIMER', wake_at: '2026-10-21T02:00:00Z', event_name: null, signal_name: null, timeout_at: null },
  variables: {},
  trigger_snapshot: { event_id: 'ev1', event_name: 'cart_item_added', source_type: 'CUSTOMER_BEHAVIOR', occurred_at: '2026-10-18T01:59:41Z', properties: {}, profile_snapshot: {} },
  trace: [], delivery_history: [], delivery_history_next_cursor: null, as_of: '2026-10-18T02:00:05Z', masked_paths: [],
})

function install(state: State, extra: Record<string, Handler> = {}) {
  const routes: Record<string, Handler> = {
    'GET /api/v1/auth/me': () => (state.me ? json({ username: 'support01' }) : problem(401, 'UNAUTHENTICATED')),
    'GET /api/v1/enrollments': () =>
      state.list ? json({ items: [summary()], next_cursor: null }) : problem(401, 'UNAUTHENTICATED'),
    'POST /api/v1/auth/login': () => {
      if (!state.loginOk) return problem(401, 'UNAUTHENTICATED')
      state.me = true
      state.list = true
      return empty()
    },
    ...extra,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, 'http://localhost')
      const h = routes[`${(init?.method ?? 'GET').toUpperCase()} ${url.pathname}`]
      return h ? h(url, init) : problem(404, 'NOT_FOUND')
    }),
  )
}
const start = (path: string) => {
  window.history.pushState({}, '', path)
  return render(<StrictMode><App /></StrictMode>)
}
/** 模擬重新載入：卸載整個 App 後在目前網址重新 render，瀏覽器儲存保持不變 */
const reload = () => {
  cleanup()
  document.body.innerHTML = ''
  return render(<StrictMode><App /></StrictMode>)
}
function clearCookies() {
  for (const c of document.cookie.split(';')) {
    const name = c.split('=')[0].trim()
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
  }
}
const storageDump = () => {
  const out: string[] = []
  for (const st of [window.localStorage, window.sessionStorage]) {
    for (let i = 0; i < st.length; i++) out.push(st.getItem(st.key(i) as string) ?? '')
  }
  return out
}
const text = () => document.body.textContent ?? ''

/** 已登入進站並查詢，之後 session 失效，再按查詢 → 401 導回登入頁 */
async function expireSession(state: State) {
  const user = userEvent.setup()
  start('/')
  await user.type(await screen.findByLabelText('客戶 ID 或 Enrollment ID'), `${PID}{Enter}`)
  await screen.findByRole('table')
  state.list = false
  state.me = false
  await user.click(screen.getByRole('button', { name: '查詢' }))
  await screen.findByLabelText('帳號')
  return user
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  window.sessionStorage.clear()
  clearCookies()
})
afterEach(() => {
  cleanup()
  document.body.innerHTML = ''
})

describe('session 逾時提示', () => {
  it('已登入後列表 401：導向 /login 並顯示「登入已逾時，請重新登入」', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state)
    await expireSession(state)
    expect(window.location.pathname).toBe('/login')
    expect(text()).toContain(EXPIRED)
    expect(text()).not.toContain(BAD_CRED)
  })

  it('登入成功後回原頁並保留 ?q=，且不再顯示逾時提示', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state)
    const user = await expireSession(state)
    await user.type(screen.getByLabelText('帳號'), 'support01')
    await user.type(screen.getByLabelText('密碼'), 'pw')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await screen.findByRole('table')
    expect(window.location.pathname).toBe('/')
    expect(window.location.search).toContain(`q=${PID}`)
    expect(text()).not.toContain(EXPIRED)
  })

  it('詳情頁 401 同樣顯示逾時提示，登入後回該詳情頁', async () => {
    const state = { me: true, list: true, loginOk: true }
    let detailOk = true
    install(state, {
      [`GET /api/v1/enrollments/${EID}`]: () => (detailOk ? json(detail()) : problem(401, 'UNAUTHENTICATED')),
    })
    const user = userEvent.setup()
    start('/')
    await user.type(await screen.findByLabelText('客戶 ID 或 Enrollment ID'), `${PID}{Enter}`)
    await screen.findByRole('table')
    detailOk = false
    state.me = false
    await user.click(screen.getAllByRole('link').find((a) => a.getAttribute('href')?.includes(EID)) as HTMLElement)
    await screen.findByLabelText('帳號')
    expect(window.location.pathname).toBe('/login')
    expect(text()).toContain(EXPIRED)
    detailOk = true
    await user.type(screen.getByLabelText('帳號'), 'support01')
    await user.type(screen.getByLabelText('密碼'), 'pw')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(window.location.pathname).toBe(`/enrollments/${EID}`))
    expect(text()).not.toContain(EXPIRED)
  })
})

describe('不應顯示逾時提示的情境', () => {
  it('未登入直接進站（/me 401）→ 導向 /login，不顯示逾時提示', async () => {
    install({ me: false, list: false, loginOk: true })
    start('/')
    await screen.findByLabelText('帳號')
    expect(window.location.pathname).toBe('/login')
    expect(text()).not.toContain(EXPIRED)
  })

  it('未登入帶 ?q= 進站 → 不顯示逾時提示', async () => {
    install({ me: false, list: false, loginOk: true })
    start(`/?q=${PID}`)
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
  })

  it('直接開 /login → 不顯示逾時提示', async () => {
    install({ me: false, list: false, loginOk: true })
    start('/login')
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
  })

  it('直接開 /login?next=… 且尚未登入 → 不顯示逾時提示（不能只靠網址參數判斷）', async () => {
    install({ me: false, list: false, loginOk: true })
    start(`/login?next=${encodeURIComponent('/?q=' + PID)}`)
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
  })

  it('未登入進站 → 登入失敗 → 只有「帳號或密碼不正確」，無逾時提示', async () => {
    install({ me: false, list: false, loginOk: false })
    const user = userEvent.setup()
    start('/login')
    await user.type(await screen.findByLabelText('帳號'), 'u')
    await user.type(screen.getByLabelText('密碼'), 'p')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(text()).toContain(BAD_CRED))
    expect(text()).not.toContain(EXPIRED)
  })

  it('登出後回登入頁 → 不顯示逾時提示', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state, {
      'POST /api/v1/auth/logout': () => {
        state.me = false
        return empty()
      },
    })
    const user = userEvent.setup()
    start('/')
    await screen.findByText('support01')
    await user.click(screen.getByRole('button', { name: '登出' }))
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
  })
})

describe('逾時提示與登入錯誤訊息互斥', () => {
  it('逾時後輸入錯誤密碼：顯示「帳號或密碼不正確」，逾時提示消失，兩者不同時出現', async () => {
    const state = { me: true, list: true, loginOk: false }
    install(state)
    const user = await expireSession(state)
    expect(text()).toContain(EXPIRED)
    expect(text()).not.toContain(BAD_CRED)
    await user.type(screen.getByLabelText('帳號'), 'support01')
    await user.type(screen.getByLabelText('密碼'), 'wrong')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(text()).toContain(BAD_CRED))
    expect(text()).not.toContain(EXPIRED)
  })

  it('逾時 → 錯誤密碼 → 再以正確密碼登入，回原頁含 ?q=', async () => {
    const state = { me: true, list: true, loginOk: false }
    install(state)
    const user = await expireSession(state)
    await user.type(screen.getByLabelText('帳號'), 'support01')
    await user.type(screen.getByLabelText('密碼'), 'wrong')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(text()).toContain(BAD_CRED))
    state.loginOk = true
    await user.type(screen.getByLabelText('密碼'), 'right')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await screen.findByRole('table')
    expect(window.location.search).toContain(`q=${PID}`)
    expect(text()).not.toContain(BAD_CRED)
    expect(text()).not.toContain(EXPIRED)
  })

  it('逾時後登入遇 5xx：顯示 request_id，逾時提示與「帳號或密碼不正確」都不出現', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state, { 'POST /api/v1/auth/login': () => problem(500, 'INTERNAL_ERROR') })
    const user = await expireSession(state)
    await user.type(screen.getByLabelText('帳號'), 'support01')
    await user.type(screen.getByLabelText('密碼'), 'pw')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(text()).toContain('REQ-INTERNAL_ERROR'))
    expect(text()).not.toContain(EXPIRED)
    expect(text()).not.toContain(BAD_CRED)
  })
})

describe('重新載入後的逾時提示（追加 1）', () => {
  const USER = 'support01'
  const PW = 'S3cretPw!x'
  const COOKIE = 'SESSIONCOOKIEVALUE98765'

  it('已登入（/me 200）後重新載入，/me 401 → 顯示逾時提示；登入後回原頁含 ?q=', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state)
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    await screen.findByRole('table')
    state.me = false
    state.list = false
    reload()
    await screen.findByLabelText('帳號')
    expect(window.location.pathname).toBe('/login')
    expect(text()).toContain(EXPIRED)
    expect(text()).not.toContain(BAD_CRED)
    await user.type(screen.getByLabelText('帳號'), USER)
    await user.type(screen.getByLabelText('密碼'), PW)
    await user.click(screen.getByRole('button', { name: '登入' }))
    await screen.findByRole('table')
    expect(window.location.pathname).toBe('/')
    expect(window.location.search).toContain(`q=${PID}`)
    expect(text()).not.toContain(EXPIRED)
  })

  it('由登入表單登入後重新載入，/me 401 → 顯示逾時提示', async () => {
    const state = { me: false, list: false, loginOk: true }
    install(state)
    const user = userEvent.setup()
    start('/login')
    await user.type(await screen.findByLabelText('帳號'), USER)
    await user.type(screen.getByLabelText('密碼'), PW)
    await user.click(screen.getByRole('button', { name: '登入' }))
    await screen.findByText(USER)
    state.me = false
    state.list = false
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).toContain(EXPIRED)
  })

  it('詳情頁重新載入遇 /me 401 → 顯示逾時提示，登入後回該詳情頁', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state, { [`GET /api/v1/enrollments/${EID}`]: () => json(detail()) })
    const user = userEvent.setup()
    start(`/enrollments/${EID}`)
    await waitFor(() => expect(text()).toContain('棄單挽回'))
    state.me = false
    state.list = false
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).toContain(EXPIRED)
    await user.type(screen.getByLabelText('帳號'), USER)
    await user.type(screen.getByLabelText('密碼'), PW)
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(window.location.pathname).toBe(`/enrollments/${EID}`))
    expect(text()).not.toContain(EXPIRED)
  })

  it('從未登入過，重新載入 /me 401 → 不顯示逾時提示', async () => {
    install({ me: false, list: false, loginOk: true })
    start(`/?q=${PID}`)
    await screen.findByLabelText('帳號')
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
  })

  it('登出後重新載入 → 不顯示逾時提示', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state, {
      'POST /api/v1/auth/logout': () => {
        state.me = false
        state.list = false
        return empty()
      },
    })
    const user = userEvent.setup()
    start('/')
    await screen.findByText(USER)
    await user.click(screen.getByRole('button', { name: '登出' }))
    await screen.findByLabelText('帳號')
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
  })

  it('逾時提示只出現一次：提示後再次重新載入（未登入）不重複顯示', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state)
    start('/')
    await screen.findByText(USER)
    state.me = false
    state.list = false
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).toContain(EXPIRED)
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
    window.history.pushState({}, '', '/login')
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).not.toContain(EXPIRED)
  })

  it('逾時後登入成功，再度登入並失效重新載入仍會提示（每次失效各提示一次）', async () => {
    const state = { me: true, list: true, loginOk: true }
    install(state)
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    await screen.findByRole('table')
    state.me = false
    state.list = false
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).toContain(EXPIRED)
    await user.type(screen.getByLabelText('帳號'), USER)
    await user.type(screen.getByLabelText('密碼'), PW)
    await user.click(screen.getByRole('button', { name: '登入' }))
    await screen.findByRole('table')
    expect(text()).not.toContain(EXPIRED)
    state.me = false
    state.list = false
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).toContain(EXPIRED)
  })

  it('瀏覽器儲存不含帳號、密碼或 cookie 值（登入後與逾時後各掃描一次）', async () => {
    document.cookie = `sid=${COOKIE}; path=/`
    const state = { me: false, list: false, loginOk: true }
    install(state)
    const user = userEvent.setup()
    start('/login')
    await user.type(await screen.findByLabelText('帳號'), USER)
    await user.type(screen.getByLabelText('密碼'), PW)
    await user.click(screen.getByRole('button', { name: '登入' }))
    await screen.findByText(USER)
    const check = () => {
      for (const v of storageDump()) {
        expect(v).not.toContain(USER)
        expect(v).not.toContain(PW)
        expect(v).not.toContain(COOKIE)
      }
    }
    check()
    state.me = false
    state.list = false
    reload()
    await screen.findByLabelText('帳號')
    expect(text()).toContain(EXPIRED)
    check()
  })
})
