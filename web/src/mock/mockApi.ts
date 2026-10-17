// 契約 §1.2、§4、§5 的 mock（後端未完成時用；INT-01 改接真實 API 後移除）。
// 以 window.fetch 層替換，畫面程式碼不知道它在跟 mock 講話。
import type { Delivery, EnrollmentDetail, EnrollmentSummary, Page } from '../api/types'

const SESSION_KEY = 'cdp.mock.session'
const MOCK_USER = 'support01'

const PROFILE = '01JCXYZPROFILE00000000001'
const E1 = '01JCXYZENROLL000000000001'
const E2 = '01JCXYZENROLL000000000002'
const E3 = '01JCXYZENROLL000000000003'

const summaries: EnrollmentSummary[] = [
  {
    enrollment_id: E1, journey_id: 'j1', profile_id: PROFILE, current_node_id: 'wait_1', current_node_type: 'WaitDelayNode',
    journey_version: 2, journey_name: '棄單挽回', current_node_name: '等 3 天', status: 'WAITING',
    enrolled_at: '2026-10-18T02:00:00Z', updated_at: '2026-10-18T02:00:05Z', terminated_at: null,
  },
  {
    enrollment_id: E2, journey_id: 'j1', profile_id: PROFILE, current_node_id: 'end_1', current_node_type: 'EndNode',
    journey_version: 2, journey_name: '棄單挽回', current_node_name: null, status: 'COMPLETED',
    enrolled_at: '2026-10-10T01:12:00Z', updated_at: '2026-10-13T01:13:00Z', terminated_at: '2026-10-13T01:13:00Z',
  },
  {
    enrollment_id: E3, journey_id: 'j2', profile_id: PROFILE, current_node_id: 'send_1', current_node_type: 'SendMessageNode',
    journey_version: 1, journey_name: '新客歡迎', current_node_name: null, status: 'FAILED',
    enrolled_at: '2026-10-01T06:30:00Z', updated_at: '2026-10-01T06:35:00Z', terminated_at: '2026-10-01T06:35:00Z',
  },
]

function deliveries(n: number): Delivery[] {
  return Array.from({ length: n }, (_, i) => ({
    idempotency_key: `idem-${i}`, node_id: 'send_1', trace_seq: 3, node_name: '發送優惠通知', template_id: 'tpl_cart_reminder',
    channel_id: 'ch_mock_1', channel_type: 'MOCK', message_type: null,
    status: i === 0 && n < 5 ? ('PENDING' as const) : ('SENT' as const), skip_reason: null,
    attempted_at: new Date(Date.UTC(2026, 9, 13, 1, 12) - i * 86400000).toISOString().replace('.000Z', 'Z'),
    delivery_id: i === 0 && n < 5 ? null : `msg_${String(i).padStart(4, '0')}`,
  }))
}

function detailOf(s: EnrollmentSummary): EnrollmentDetail {
  const waiting = s.status === 'WAITING'
  const many = s.enrollment_id === E3 ? deliveries(60) : s.enrollment_id === E2 ? deliveries(1) : []
  return {
    ...s,
    termination_reason: s.status === 'COMPLETED' ? '已走到終點' : s.status === 'FAILED' ? '節點執行失敗' : null,
    wake_condition: waiting ? { type: 'TIMER', wake_at: '2026-10-21T02:00:00Z', event_name: null, signal_name: null, timeout_at: null } : null,
    variables: {},
    trigger_snapshot: {
      event_id: '01JCXYZ8K3M4N5P6Q7R8S9T0AB', event_name: 'cart_item_added', source_type: 'CUSTOMER_BEHAVIOR',
      occurred_at: '2026-10-18T01:59:41Z',
      properties: { cart_id: 'CART-99201', cart_total: 1580, email: 'a***@example.com', phone: '***' },
      profile_snapshot: {},
    },
    trace: [
      {
        seq: 1, node_id: 'src_1', node_type: 'EventSourceNode', node_name: '加入購物車', entered_at: s.enrolled_at, exited_at: s.enrolled_at,
        out_port: 'next', decision_reason: '收到事件 cart_item_added，建立 Enrollment。', evaluated: { event_id: '01JCXYZ8K3M4N5P6Q7R8S9T0AB' },
      },
      {
        seq: 2, node_id: 'wait_1', node_type: 'WaitDelayNode', node_name: waiting ? '等 3 天' : null, entered_at: s.enrolled_at,
        exited_at: waiting ? null : s.updated_at, out_port: waiting ? null : 'next',
        decision_reason: '等待 P3D，預計 2026-10-21T02:00:00Z 喚醒。', evaluated: { duration: 'P3D', wake_at: '2026-10-21T02:00:00Z', phone: '***' },
      },
    ],
    delivery_history: many.slice(0, 50),
    delivery_history_next_cursor: many.length > 50 ? '50' : null,
    as_of: new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
    masked_paths: ['trace[1].evaluated.phone', 'trigger_snapshot.properties.email', 'trigger_snapshot.properties.phone'],
  }
}

const reqId = () => 'MOCK' + Math.random().toString(36).slice(2, 10).toUpperCase()
const ok = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
const problem = (status: number, code: string, detail: string) => {
  const request_id = reqId()
  return new Response(JSON.stringify({ type: 'about:blank', title: String(status), status, code, detail, request_id }), {
    status,
    headers: { 'Content-Type': 'application/problem+json', 'X-Request-Id': request_id },
  })
}

function paginate<T>(all: T[], cursor: string | null, limit: number): Page<T> {
  const start = cursor ? Number(cursor) || 0 : 0
  const items = all.slice(start, start + limit)
  return { items, next_cursor: start + limit < all.length ? String(start + limit) : null }
}

/** 依契約回應；session 以 sessionStorage 的旗標模擬 CDP_SESSION cookie。 */
export function mockHandle(method: string, rawUrl: string, body: string | undefined): Response {
  const url = new URL(rawUrl, 'http://localhost')
  const path = url.pathname
  const signedIn = sessionStorage.getItem(SESSION_KEY) === '1'

  if (method === 'POST' && path === '/api/v1/auth/login') {
    let creds: { username?: string; password?: string } = {}
    try {
      creds = JSON.parse(body ?? '{}')
    } catch {
      /* 視為空 */
    }
    if (!creds.username || !creds.password) return problem(400, 'VALIDATION_FAILED', '請輸入帳號與密碼。')
    if (creds.username !== MOCK_USER) return problem(401, 'INVALID_CREDENTIALS', '帳號或密碼不正確。')
    sessionStorage.setItem(SESSION_KEY, '1')
    return new Response(null, { status: 204 })
  }
  if (method === 'POST' && path === '/api/v1/auth/logout') {
    sessionStorage.removeItem(SESSION_KEY)
    return new Response(null, { status: 204 })
  }
  if (!signedIn) return problem(401, 'UNAUTHENTICATED', '請先登入。')
  if (method === 'GET' && path === '/api/v1/auth/me') return ok({ username: MOCK_USER })
  if (method !== 'GET') return problem(404, 'NOT_FOUND', '找不到。')

  const limit = Math.min(100, Number(url.searchParams.get('limit')) || 20)
  const cursor = url.searchParams.get('cursor')
  if (path === '/api/v1/enrollments') {
    const q = url.searchParams.get('q')
    if (!q) return problem(400, 'VALIDATION_FAILED', 'q 必填。')
    const statuses = (url.searchParams.get('status') ?? '').split(',').filter(Boolean)
    const hit = summaries.filter(
      (s) => (s.profile_id === q || s.enrollment_id === q) && (statuses.length === 0 || statuses.includes(s.status)),
    )
    return ok(paginate(hit, cursor, limit))
  }
  const m = /^\/api\/v1\/enrollments\/([^/]+)(\/deliveries)?$/.exec(path)
  if (m) {
    const s = summaries.find((x) => x.enrollment_id === decodeURIComponent(m[1]))
    if (!s) return problem(404, 'ENROLLMENT_NOT_FOUND', '找不到這筆 Enrollment。')
    const d = detailOf(s)
    if (!m[2]) return ok(d)
    return ok(paginate(s.enrollment_id === E3 ? deliveries(60) : [], cursor, 50))
  }
  return problem(404, 'NOT_FOUND', '找不到。')
}

export function installMockApi() {
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    await new Promise((r) => setTimeout(r, 200))
    return mockHandle((init?.method ?? 'GET').toUpperCase(), url, typeof init?.body === 'string' ? init.body : undefined)
  }
}
