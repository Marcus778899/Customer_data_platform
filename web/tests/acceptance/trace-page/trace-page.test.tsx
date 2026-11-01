// @vitest-environment jsdom
/**
 * FE-01 驗收測試（qa 撰寫，worker 不可改）。只透過畫面行為驗證，mock 全域 fetch。
 *
 * worker 需提供的進入點：
 *   - `web/src/App.tsx` 具名匯出 `App`（無 props），自行依 window.location 路由
 *     （/login、/、/enrollments/:id；用 History API，測試以 history.pushState 設定起始網址）。
 *   - 所有 API 呼叫走全域 `fetch`，路徑為契約的 `/api/v1/...`。
 *   - 已登入判斷：呼叫 GET /api/v1/auth/me；401 → 導向 /login。
 *
 * 測試相依（目前未安裝，見回報）：@testing-library/react、@testing-library/user-event、jsdom。
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../../src/App'
import { AMY, logoutControl } from '../shell/helpers'

process.env.TZ = 'Asia/Taipei' // 本地時區 = UTC+8，用來驗證 D-016

const EID = '01JCXYZENROLL000000000001'
const PID = '01JCXYZPROFILE00000000001'

type Handler = (url: URL, init?: RequestInit) => Response | Promise<Response>
let calls: { method: string; url: URL; body?: string }[] = []

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } })
const problem = (status: number, code: string, request_id = 'REQ-' + code) =>
  json({ type: 'about:blank', title: 'x', status, code, detail: 'd', request_id }, status)
const empty = (status = 204) => new Response(null, { status })

function summary(over: Record<string, unknown> = {}) {
  return {
    enrollment_id: EID, journey_id: 'j1', profile_id: PID, current_node_id: 'wait_1', current_node_type: 'WaitDelayNode',
    journey_version: 2, journey_name: '棄單挽回', current_node_name: '等 3 天', status: 'WAITING',
    enrolled_at: '2026-10-18T02:00:00Z', updated_at: '2026-10-18T02:00:05Z', terminated_at: null, ...over,
  }
}
function delivery(i: number, over: Record<string, unknown> = {}) {
  return {
    idempotency_key: 'idem-' + i, node_id: 'send_1', trace_seq: 3, node_name: '發送優惠通知', template_id: 'tpl_' + i,
    channel_id: 'ch_mock_1', channel_type: 'MOCK', message_type: null, status: 'SENT', skip_reason: null,
    attempted_at: '2026-10-13T01:12:00Z', delivery_id: 'd' + i, ...over,
  }
}
function detail(over: Record<string, unknown> = {}) {
  return {
    ...summary(), termination_reason: null,
    wake_condition: { type: 'TIMER', wake_at: '2026-10-21T02:00:00Z', event_name: null, signal_name: null, timeout_at: null },
    variables: {},
    trigger_snapshot: { event_id: 'ev1', event_name: 'cart_item_added', source_type: 'CUSTOMER_BEHAVIOR', occurred_at: '2026-10-18T01:59:41Z', properties: { cart_id: 'CART-99201' }, profile_snapshot: {} },
    trace: [
      { seq: 1, node_id: 'src_1', node_type: 'EventSourceNode', node_name: '加入購物車', entered_at: '2026-10-18T02:00:00Z', exited_at: '2026-10-18T02:00:00Z', out_port: 'next', decision_reason: '收到事件 cart_item_added，建立 Enrollment。', evaluated: { event_id: 'ev1' } },
      { seq: 2, node_id: 'wait_1', node_type: 'WaitDelayNode', node_name: null, entered_at: '2026-10-18T02:00:00Z', exited_at: null, out_port: null, decision_reason: '等待 P3D，預計 2026-10-21T02:00:00Z 喚醒。', evaluated: { duration: 'P3D', wake_at: '2026-10-21T02:00:00Z' } },
    ],
    delivery_history: [], delivery_history_next_cursor: null, as_of: '2026-10-18T02:00:05Z', masked_paths: [], ...over,
  }
}

function setup(extra: Record<string, Handler> = {}, authed = true) {
  const routes: Record<string, Handler> = {
    'GET /api/v1/auth/me': () => (authed ? json(AMY) : problem(401, 'UNAUTHENTICATED')),
    'POST /api/v1/auth/logout': () => empty(),
    ...extra,
  }
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, 'http://localhost')
    const method = (init?.method ?? 'GET').toUpperCase()
    calls.push({ method, url, body: typeof init?.body === 'string' ? init.body : undefined })
    const h = routes[`${method} ${url.pathname}`]
    if (!h) return problem(404, 'NOT_FOUND')
    return h(url, init)
  }))
}
const start = (path: string) => { window.history.pushState({}, '', path); return render(<App />) }
const lastCall = (m: string, p: string) => [...calls].reverse().find((c) => c.method === m && c.url.pathname === p)
const h1 = () => screen.findByRole('heading', { level: 1, name: /棄單挽回/ })

beforeEach(() => { vi.unstubAllGlobals() })
afterEach(() => { cleanup() })

describe('登入頁', () => {
  it('未登入（/me 401）導向 /login，顯示 Email、密碼欄位，密碼欄有 autocomplete', async () => {
    setup({}, false)
    start('/')
    await screen.findByLabelText('Email')
    expect(window.location.pathname).toBe('/login')
    expect(screen.getByLabelText('密碼')).toHaveProperty('type', 'password')
    expect(screen.getByLabelText('密碼').getAttribute('autocomplete')).toBeTruthy()
  })

  it('深層連結遇 401 導向登入，登入成功（204）後回原頁', async () => {
    let loggedIn = false
    setup({
      'GET /api/v1/auth/me': () => (loggedIn ? json(AMY) : problem(401, 'UNAUTHENTICATED')),
      [`GET /api/v1/enrollments/${EID}`]: () => (loggedIn ? json(detail()) : problem(401, 'UNAUTHENTICATED')),
      'POST /api/v1/auth/login': () => { loggedIn = true; return empty() },
    })
    const user = userEvent.setup()
    start(`/enrollments/${EID}`)
    await user.type(await screen.findByLabelText('Email'), 'amy.chen@example.com')
    await user.type(screen.getByLabelText('密碼'), 'pw')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await h1()
    expect(window.location.pathname).toBe(`/enrollments/${EID}`)
    expect(JSON.parse(lastCall('POST', '/api/v1/auth/login')!.body!)).toEqual({ email: 'amy.chen@example.com', password: 'pw' })
  })

  it('401 只顯示「Email 或密碼不正確。」（role=alert），400 於欄位下顯示「請輸入 Email。」「請輸入密碼。」（非 alert），5xx 附 request_id', async () => {
    let mode = 401
    setup({ 'POST /api/v1/auth/login': () => (mode === 401 ? problem(401, 'INVALID_CREDENTIALS') : mode === 400 ? problem(400, 'VALIDATION_FAILED') : problem(500, 'INTERNAL_ERROR', 'REQ-LOGIN-500')) }, false)
    const user = userEvent.setup()
    start('/login')
    await user.type(await screen.findByLabelText('Email'), 'a')
    await user.type(screen.getByLabelText('密碼'), 'b')
    await user.click(screen.getByRole('button', { name: '登入' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Email 或密碼不正確。')
    expect(screen.getByLabelText('Email').getAttribute('aria-describedby')).toBeTruthy()
    mode = 400
    await user.clear(screen.getByLabelText('Email'))
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(document.body.textContent).toContain('請輸入 Email。'))
    expect(document.body.textContent).not.toContain('Email 或密碼不正確。')
    mode = 500
    await user.type(screen.getByLabelText('密碼'), 'b')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('REQ-LOGIN-500'))
  })

  it('任何 API 回 401（查詢列表時）導回 /login', async () => {
    setup({ 'GET /api/v1/enrollments': () => problem(401, 'UNAUTHENTICATED') })
    const user = userEvent.setup()
    start('/')
    await user.type(await screen.findByLabelText(/客戶 ID 或\s?(Enrollment ID|進度編號)/), PID)
    await user.click(screen.getByRole('button', { name: '查詢' }))
    await screen.findByLabelText('Email')
    expect(window.location.pathname).toBe('/login')
  })

  it('外框帳號區顯示「已登入：姓名」與「登出」；登出 POST /auth/logout 後回登入頁', async () => {
    setup()
    const user = userEvent.setup()
    start('/')
    await waitFor(() => expect(document.body.textContent).toContain('已登入：陳小美'))
    await user.click(logoutControl())
    await screen.findByLabelText('Email')
    expect(lastCall('POST', '/api/v1/auth/logout')).toBeTruthy()
    expect(window.location.pathname).toBe('/login')
  })
})

describe('查詢列表', () => {
  const listRoute = (items: unknown[], next: string | null = null): Handler => () => json({ items, next_cursor: next })

  it('初始顯示提示；Enter 送出帶 q 查詢並同步網址 ?q=，列表含旅程名、狀態文字、連結', async () => {
    setup({ 'GET /api/v1/enrollments': listRoute([summary(), summary({ enrollment_id: 'E2', status: 'COMPLETED', terminated_at: '2026-10-19T00:00:00Z' })]) })
    const user = userEvent.setup()
    start('/')
    expect(await screen.findByText(/輸入客戶 ID 或\s?(Enrollment ID|進度編號)\s?開始查詢/)).toBeTruthy()
    await user.type(screen.getByLabelText(/客戶 ID 或\s?(Enrollment ID|進度編號)/), `${PID}{Enter}`)
    const table = await screen.findByRole('table')
    expect(table.querySelector('caption')).toBeTruthy()
    expect(table.querySelectorAll('th[scope="col"]').length).toBeGreaterThan(0)
    expect(lastCall('GET', '/api/v1/enrollments')!.url.searchParams.get('q')).toBe(PID)
    expect(window.location.search).toContain(`q=${PID}`)
    expect(within(table).getAllByRole('link').length).toBe(2)
    expect(table.textContent).toContain('棄單挽回')
    expect(table.textContent).toContain('等待中')
    expect(table.textContent).toContain('已完成')
  })

  it('網址帶 ?q= 直接查詢；狀態篩選帶 status 參數', async () => {
    setup({ 'GET /api/v1/enrollments': listRoute([summary()]) })
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    await screen.findByRole('table')
    expect(lastCall('GET', '/api/v1/enrollments')!.url.searchParams.get('q')).toBe(PID)
    await user.selectOptions(screen.getByLabelText('狀態'), screen.getByRole('option', { name: /WAITING/ }))
    await user.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(lastCall('GET', '/api/v1/enrollments')!.url.searchParams.get('status')).toBe('WAITING'))
  })

  it('查無（items: []）顯示說明，不是錯誤', async () => {
    setup({ 'GET /api/v1/enrollments': listRoute([]) })
    start('/?q=UNKNOWN1')
    await screen.findByText(/找不到「UNKNOWN1」的任何旅程紀錄/)
    expect(screen.getByText(/可能原因/)).toBeTruthy()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('5xx 顯示 role=alert、request_id 與「重試」，重試重打同一請求', async () => {
    let n = 0
    setup({ 'GET /api/v1/enrollments': () => (++n === 1 ? problem(503, 'SERVICE_UNAVAILABLE', 'REQ-LIST-1') : json({ items: [summary()], next_cursor: null })) })
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('REQ-LIST-1')
    await user.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('table')
    expect(n).toBe(2)
  })

  it('400 也顯示錯誤與 request_id', async () => {
    setup({ 'GET /api/v1/enrollments': () => problem(400, 'VALIDATION_FAILED', 'REQ-400') })
    start('/?q=x')
    expect((await screen.findByRole('alert')).textContent).toContain('REQ-400')
  })

  it('有 next_cursor 才顯示「載入更多」，點擊帶 cursor 並附加列；沒有則不出現', async () => {
    setup({
      'GET /api/v1/enrollments': (url) =>
        url.searchParams.get('cursor') === 'C1'
          ? json({ items: [summary({ enrollment_id: 'E_PAGE2' })], next_cursor: null })
          : json({ items: [summary()], next_cursor: 'C1' }),
    })
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: '載入更多' }))
    await waitFor(() => expect(within(screen.getByRole('table')).getAllByRole('link').length).toBe(2))
    expect(screen.getByRole('table').textContent).toContain('E_PAGE2')
    expect(screen.queryByRole('button', { name: '載入更多' })).toBeNull()
  })
})

describe('詳情', () => {
  const withDetail = (d: unknown, extra: Record<string, Handler> = {}) =>
    setup({ [`GET /api/v1/enrollments/${EID}`]: () => json(d), ...extra })

  it('從列表點連結進詳情，網址 /enrollments/:id，焦點移到 h1', async () => {
    setup({
      'GET /api/v1/enrollments': () => json({ items: [summary()], next_cursor: null }),
      [`GET /api/v1/enrollments/${EID}`]: () => json(detail()),
    })
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    await user.click(within(await screen.findByRole('table')).getAllByRole('link')[0])
    const heading = await h1()
    expect(window.location.pathname).toBe(`/enrollments/${EID}`)
    await waitFor(() => expect(document.activeElement).toBe(heading))
  })

  it('步驟時間軸：<ol> 由舊到新、白話標題與狀態字、目前步驟 aria-current=step、不出現節點型別名與出口', async () => {
    withDetail(detail())
    start(`/enrollments/${EID}`)
    await h1()
    const h = await screen.findByRole('heading', { name: '走過的步驟' })
    let box: HTMLElement | null = h
    let ol: Element | undefined
    while (box && !ol) { ol = [...box.querySelectorAll('ol')].find((o) => !o.closest('nav')); box = box.parentElement }
    const items = [...ol!.querySelectorAll(':scope > li')]
    expect(items).toHaveLength(2)
    expect(items[0].textContent).toContain('加入購物車，進入旅程')
    expect(items[0].textContent).toContain('已完成')
    expect(items[0].getAttribute('aria-current')).toBeNull()
    expect(items[1].getAttribute('aria-current')).toBe('step')
    expect(items[1].textContent).toContain('目前在這裡')
    for (const li of items) {
      expect(li.textContent).not.toMatch(/EventSourceNode|WaitDelayNode|出口|P3D|UTC/)
    }
  })

  it('發送紀錄為空：說明何時會出現；profile_snapshot 為 {} 的「此版本未記錄客戶屬性快照」收在進階資訊', async () => {
    withDetail(detail())
    start(`/enrollments/${EID}`)
    await screen.findByText('還沒有發出任何訊息', { exact: false })
    const adv = [...document.querySelectorAll('details')].find((d) => d.textContent?.includes('此版本未記錄客戶屬性快照'))
    expect(adv).toBeTruthy()
    expect(adv!.querySelector('summary')!.textContent).toContain('進階資訊')
    expect(adv!.open).toBe(false)
  })

  it('COMPLETED 但有 PENDING 發送：摘要寫排隊中與聯絡技術人員，發送紀錄結果為「等待發送」', async () => {
    withDetail(detail({ status: 'COMPLETED', wake_condition: null, terminated_at: '2026-10-13T01:13:00Z', termination_reason: '已走到終點', delivery_history: [delivery(1, { status: 'PENDING', delivery_id: null })] }))
    start(`/enrollments/${EID}`)
    const card = (await screen.findAllByRole('status')).find((s) => s.textContent?.includes('這位客戶現在在哪'))!
    expect(card.textContent).toContain('旅程已走完')
    expect(card.textContent).toContain('1 則訊息在排隊')
    expect(card.textContent).toContain('聯絡技術人員')
    expect(screen.getByRole('table').textContent).toContain('等待發送')
  })

  it('訊息發送紀錄表：時間、哪一步、管道、結果；MOCK 顯示測試管道；各結果白話；略過原因白話；沒有技術代碼', async () => {
    withDetail(detail({ delivery_history: [
      delivery(1), delivery(2, { status: 'FAILED', delivery_id: null }), delivery(3, { status: 'CANCELLED', delivery_id: null }),
      delivery(4, { status: 'SKIPPED', skip_reason: 'FREQUENCY_CAP', delivery_id: null }),
    ] }))
    start(`/enrollments/${EID}`)
    const table = await screen.findByRole('table')
    expect(table.querySelector('caption')).toBeTruthy()
    expect([...table.querySelectorAll('th[scope="col"]')].map((t) => t.textContent)).toEqual(['時間', '哪一步', '管道', '結果'])
    for (const t of ['測試管道（不會真的送出）', '已送出', '發送失敗', '已取消', '未發送', '超過發送次數上限']) expect(table.textContent).toContain(t)
    for (const t of ['tpl_1', 'ch_mock_1', 'MOCK', 'FREQUENCY_CAP', 'idem-1']) expect(table.textContent).not.toContain(t)
  })

  it('「載入更早紀錄」帶 delivery_history_next_cursor 呼叫 /deliveries 並附加；無更多時消失', async () => {
    withDetail(detail({ delivery_history: [delivery(1)], delivery_history_next_cursor: 'DC1' }), {
      [`GET /api/v1/enrollments/${EID}/deliveries`]: () => json({ items: [delivery(2, { attempted_at: '2026-10-12T01:12:00Z' })], next_cursor: null }),
    })
    const user = userEvent.setup()
    start(`/enrollments/${EID}`)
    const rowCount = () => within(screen.getByRole('table')).getAllByRole('row').length
    await user.click(await screen.findByRole('button', { name: '載入更早紀錄' }))
    await waitFor(() => expect(rowCount()).toBe(3)) // 表頭 + 2 筆
    expect(lastCall('GET', `/api/v1/enrollments/${EID}/deliveries`)!.url.searchParams.get('cursor')).toBe('DC1')
    expect(screen.queryByRole('button', { name: '載入更早紀錄' })).toBeNull()
  })

  it('遮罩：只有 masked_paths 列出的值標「已遮罩」（不比對 ***），有說明；遮罩值無解除、複製入口', async () => {
    const base = detail()
    withDetail(detail({
      trace: [base.trace[0], { ...base.trace[1], evaluated: { wake_at: '2026-10-21T02:00:00Z', phone: '***' } }],
      trigger_snapshot: { event_id: 'ev1', event_name: 'cart_item_added', source_type: 'CUSTOMER_BEHAVIOR', occurred_at: '2026-10-18T01:59:41Z', properties: { email: 'a***@example.com', note: '***', cart_total: 1580 }, profile_snapshot: {} },
      masked_paths: ['trace[1].evaluated.phone', 'trigger_snapshot.properties.email'],
    }))
    start(`/enrollments/${EID}`)
    await h1()
    expect(document.body.textContent).toContain('無法解除')
    const dd = (key: string) => [...document.querySelectorAll('dt')].find((e) => e.textContent === key)!.nextElementSibling!
    expect(dd('phone').textContent).toContain('已遮罩')
    expect(dd('email').textContent).toContain('已遮罩')
    expect(dd('note').textContent).not.toContain('已遮罩') // 值是 *** 但不在 masked_paths
    expect(dd('cart_total').textContent).not.toContain('已遮罩')
    expect(dd('phone').querySelector('button')).toBeNull()
    expect(dd('email').querySelector('button')).toBeNull()
    expect(screen.queryByRole('button', { name: /解除|顯示原文|還原|unmask/i })).toBeNull()
  })

  it('時間以瀏覽器本地時區（UTC+8）顯示；UTC 原值只在進階資訊裡可見', async () => {
    expect(new Date('2026-10-18T02:00:00Z').getHours()).toBe(10) // 確認測試環境時區已生效
    withDetail(detail())
    start(`/enrollments/${EID}`)
    await h1()
    const adv = [...document.querySelectorAll('details')].find((d) => d.querySelector('summary')?.textContent?.includes('進階資訊'))!
    expect(adv.textContent).toMatch(/2026-10-18 10:00/)
    expect(adv.textContent).toContain('2026-10-21T02:00:00Z') // UTC 原值（D-016）
    const clone = document.querySelector('main')!.cloneNode(true) as HTMLElement
    clone.querySelectorAll('details').forEach((n) => n.remove())
    expect(clone.textContent).not.toMatch(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/)
    expect(clone.textContent).not.toContain('UTC')
    expect(screen.queryByLabelText(/同時顯示 UTC/)).toBeNull()
  })

  it('終態：結束原因（系統文字）只在進階資訊；「重新整理」重打同一請求並仍顯示「更新」', async () => {
    let n = 0
    setup({ [`GET /api/v1/enrollments/${EID}`]: () => { n++; return json(detail({ status: 'FAILED', wake_condition: null, terminated_at: '2026-10-19T03:04:00Z', termination_reason: '節點執行失敗', as_of: '2026-10-19T04:00:00Z' })) } })
    const user = userEvent.setup()
    start(`/enrollments/${EID}`)
    await h1()
    expect(document.body.textContent).toContain('發生錯誤')
    const clone = document.querySelector('main')!.cloneNode(true) as HTMLElement
    clone.querySelectorAll('details').forEach((x) => x.remove())
    expect(clone.textContent).not.toContain('節點執行失敗')
    expect(document.body.textContent).toContain('節點執行失敗')
    await user.click(screen.getByRole('button', { name: '重新整理' }))
    await waitFor(() => expect(n).toBe(2))
    expect(document.querySelector('main')!.textContent).toMatch(/更新/)
  })

  it('404 ENROLLMENT_NOT_FOUND 顯示「找不到這筆進度」＋錯誤代碼＋回客戶搜尋；5xx 另寫並附 request_id', async () => {
    setup({ [`GET /api/v1/enrollments/${EID}`]: () => problem(404, 'ENROLLMENT_NOT_FOUND', 'REQ-404') })
    start(`/enrollments/${EID}`)
    const a = await screen.findByRole('alert')
    expect(a.textContent).toContain('找不到這筆進度')
    expect(a.textContent).not.toContain('Enrollment')
    expect(document.body.textContent).toContain('REQ-404')
    expect(within(a).getByRole('link', { name: '回到客戶搜尋' }).getAttribute('href')).toBe('/customers')
    cleanup()
    setup({ [`GET /api/v1/enrollments/${EID}`]: () => problem(500, 'INTERNAL_ERROR', 'REQ-500') })
    start(`/enrollments/${EID}`)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('REQ-500')
    expect(alert.textContent).not.toContain('找不到這筆進度')
  })

  it('載入中顯示 role=status 且 aria-busy', async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    setup({ [`GET /api/v1/enrollments/${EID}`]: async () => { await gate; return json(detail()) } })
    start(`/enrollments/${EID}`)
    const status = await screen.findByRole('status')
    expect(status.getAttribute('aria-busy')).toBe('true')
    release()
    await h1()
  })
})

describe('唯讀與鍵盤', () => {
  const nav = () => setup({
    'GET /api/v1/enrollments': () => json({ items: [summary()], next_cursor: null }),
    [`GET /api/v1/enrollments/${EID}`]: () => json(detail({ delivery_history: [delivery(1)] })),
  })

  it('列表與詳情沒有任何寫入操作（暫停、重送、編輯、刪除…），且不發出非 GET 請求（auth 除外）', async () => {
    nav()
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    await user.click(within(await screen.findByRole('table')).getAllByRole('link')[0])
    await h1()
    expect(screen.queryByRole('button', { name: /暫停|恢復|重送|重新發送|編輯|修改|刪除|取消發送|終止|pause|resend|edit|delete/i })).toBeNull()
    expect(calls.filter((c) => c.method !== 'GET' && !c.url.pathname.includes('/auth/'))).toEqual([])
  })

  it('鍵盤：Tab 可到達查詢按鈕與列表連結，Enter 進詳情；展開區是原生 <details>', async () => {
    nav()
    const user = userEvent.setup()
    start(`/?q=${PID}`)
    const link = within(await screen.findByRole('table')).getAllByRole('link')[0]
    await user.click(screen.getByLabelText(/客戶 ID 或\s?(Enrollment ID|進度編號)/))
    const seen = new Set<Element | null>()
    for (let i = 0; i < 6; i++) { await user.tab(); seen.add(document.activeElement) }
    expect(seen.has(screen.getByRole('button', { name: '查詢' }))).toBe(true)
    expect(seen.has(link)).toBe(true)
    link.focus()
    await user.keyboard('{Enter}')
    await h1()
    const sums = [...document.querySelectorAll('details > summary')].map((s) => s.textContent ?? '')
    expect(sums.some((s) => /判斷時取得的值/.test(s))).toBe(true)
    expect(sums.some((s) => /進階資訊/.test(s))).toBe(true)
  })
})
