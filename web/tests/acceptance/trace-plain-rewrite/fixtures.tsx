/**
 * trace-plain-rewrite 驗收測試共用工具（qa 撰寫，worker 不可改）。
 * 規格：docs/ui/enrollment-trace.md；設計稿 docs/ui/enrollment-trace/plain.html。契約：journey-engine §1.2、§4、§5。
 *
 * 假設：
 *   - 進入點 App（同既有測試）；軌跡頁網址 /enrollments/:id；請求走全域 fetch。
 *   - 「主要區」= main 元素去掉所有 <details>（進階資訊、圖形軌跡都收在 details 裡）。D-079／D-080 的禁字只查主要區。
 *   - 時間：Date 以 vi.setSystemTime 固定（只假造 Date，不動 timer），時區 Asia/Taipei（UTC+8）。
 *   - 本檔自帶 builder，不依賴 journey-customer-ui/fixtures.tsx（那份屬 trace-plain-list）。
 */
import { screen } from '@testing-library/react'
import { vi } from 'vitest'
import { AMY, json, mockApi, problem, resetEnv, start, teardown, type Api, type Call } from '../shell/helpers'

export { AMY, json, problem, resetEnv, start, teardown }
export type { Api, Call }

process.env.TZ = 'Asia/Taipei'

export const JID = '01JCXYZJOURNEY0000000001'
export const PID = '01JCX7K2M4N8P3Q6R9S5T1V0WZ'
export const EID = '01JD3A5B7C9D1E3F5G7H9J2K4M'
export const VER = `/api/v1/journeys/${JID}/versions/3`
export const DETAIL = `/api/v1/enrollments/${EID}`

/** 進行中情境的「現在」：2026-10-18（日）11:00 本地 */
export const NOW_EARLY = new Date('2026-10-18T03:00:00Z')
/** 終態情境的「現在」：2026-10-22（四）12:00 本地，所有事件都在過去且不同日 */
export const NOW_LATE = new Date('2026-10-22T04:00:00Z')

export function freezeNow(d: Date) {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(d)
}
export function unfreeze() {
  vi.useRealTimers()
}

export function installPolyfills() {
  const g = globalThis as Record<string, unknown>
  if (!g.ResizeObserver) g.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
  if (!g.DOMMatrixReadOnly) g.DOMMatrixReadOnly = class { m22 = 1 }
  if (!g.IntersectionObserver) {
    g.IntersectionObserver = class { observe() {} unobserve() {} disconnect() {} takeRecords() { return [] } }
  }
}

export const problemWithId = (status: number, code: string, request_id: string) =>
  json({ type: 'about:blank', title: 'x', status, code, detail: 'd', request_id }, status, { 'X-Request-Id': request_id })

/** 登入後的後端；routes 以 "METHOD /api/v1/路徑" 為鍵 */
export function backend(routes: Record<string, (c: Call) => Response | Promise<Response>> = {}): Api {
  installPolyfills()
  return mockApi({ me: AMY, routes })
}

/** 直線旅程：開始 → 加入購物車 → 等 3 天 → 提醒信 → 結束 */
export function journeyVersion(over: Record<string, unknown> = {}) {
  return {
    journey_id: JID, name: '棄單挽回', version: 3, description: null, settings: {}, entry_node: 'start',
    nodes: [
      { id: 'start', type: 'StartNode', name: '開始', config: {}, outputs: { next: 'src_1' } },
      { id: 'src_1', type: 'EventSourceNode', name: '加入購物車', config: { event_name: 'cart_item_added' }, outputs: { next: 'wait_1' } },
      { id: 'wait_1', type: 'WaitDelayNode', name: '等 3 天', config: { duration: 'P3D' }, outputs: { next: 'send_1' } },
      { id: 'send_1', type: 'SendMessageNode', name: '提醒信', config: { template_id: 'tpl_cart_reminder', channel_id: 'ch_email' }, outputs: { sent: 'end_1' } },
      { id: 'end_1', type: 'EndNode', name: '結束', config: {}, outputs: {} },
    ],
    layout: null, published_at: '2026-10-18T01:12:00Z', ...over,
  }
}

export function traceEntry(seq: number, node_id: string, node_type: string, node_name: string | null, over: Record<string, unknown> = {}) {
  return {
    seq, node_id, node_type, node_name, entered_at: '2026-10-18T02:00:00Z', exited_at: '2026-10-18T02:00:00Z', out_port: 'next',
    decision_reason: `原因-${node_id}`, evaluated: {}, ...over,
  }
}

export function delivery(i: number, over: Record<string, unknown> = {}) {
  return {
    idempotency_key: `idem-${i}`, node_id: 'send_1', trace_seq: 3, node_name: '提醒信', template_id: `tpl_${i}`,
    channel_id: 'ch_mock_1', channel_type: 'MOCK', message_type: null, status: 'SENT', skip_reason: null,
    attempted_at: '2026-10-20T02:00:05Z', delivery_id: `dlv-${i}`, ...over,
  }
}

const WAIT_TRACE = [
  traceEntry(1, 'src_1', 'EventSourceNode', '加入購物車', {
    decision_reason: '收到事件 cart_item_added，建立 Enrollment。', evaluated: { event_id: 'ev-plain-1' },
  }),
  traceEntry(2, 'wait_1', 'WaitDelayNode', '等 3 天', {
    exited_at: null, out_port: null, decision_reason: '等待 P3D，預計 2026-10-21T02:00:00Z 喚醒。',
    evaluated: { duration: 'P3D', wake_at: '2026-10-21T02:00:00Z' },
  }),
]

/** 等待中（情境 1）：走過 src_1，停在 wait_1；10/18 02:00Z 進入，10/21 02:00Z 喚醒 */
export function waiting(over: Record<string, unknown> = {}) {
  return {
    enrollment_id: EID, journey_id: JID, profile_id: PID, current_node_id: 'wait_1', current_node_type: 'WaitDelayNode',
    journey_version: 3, journey_name: '棄單挽回', current_node_name: '等 3 天', status: 'WAITING',
    enrolled_at: '2026-10-18T02:00:00Z', updated_at: '2026-10-18T02:00:05Z', terminated_at: null, termination_reason: null,
    wake_condition: { type: 'TIMER', wake_at: '2026-10-21T02:00:00Z', event_name: null, signal_name: null, timeout_at: null },
    variables: {},
    trigger_snapshot: {
      event_id: 'ev1', event_name: 'cart_item_added', source_type: 'CUSTOMER_BEHAVIOR', occurred_at: '2026-10-18T01:59:41Z',
      properties: { cart_id: 'CART-99201' }, profile_snapshot: {},
    },
    trace: WAIT_TRACE, delivery_history: [], delivery_history_next_cursor: null,
    as_of: '2026-10-18T03:05:12Z', masked_paths: [], ...over,
  }
}

/** 走完（終態，訊息情境由 delivery_history 決定）：全程在 10/18～10/20，最後一步 10/20 10:00 */
export function completed(over: Record<string, unknown> = {}) {
  return waiting({
    status: 'COMPLETED', current_node_id: 'end_1', current_node_type: 'EndNode', current_node_name: '結束',
    terminated_at: '2026-10-20T02:00:03Z', termination_reason: '旅程走到結束節點，Enrollment 完成。', wake_condition: null,
    trace: [
      WAIT_TRACE[0],
      traceEntry(2, 'wait_1', 'WaitDelayNode', '等 3 天', { exited_at: '2026-10-20T02:00:02Z', decision_reason: '等待 P3D 完成。' }),
      traceEntry(3, 'send_1', 'SendMessageNode', '提醒信', { entered_at: '2026-10-20T02:00:02Z', exited_at: '2026-10-20T02:00:03Z', out_port: 'sent', decision_reason: '已送出 1 則。' }),
      traceEntry(4, 'end_1', 'EndNode', '結束', { entered_at: '2026-10-20T02:00:03Z', exited_at: '2026-10-20T02:00:03Z', out_port: null, decision_reason: '旅程走到結束節點。' }),
    ],
    delivery_history: [delivery(1)],
    ...over,
  })
}

/** 發生錯誤：停在 send_1 */
export function failed(over: Record<string, unknown> = {}) {
  const base = completed()
  return waiting({
    status: 'FAILED', current_node_id: 'send_1', current_node_type: 'SendMessageNode', current_node_name: '提醒信',
    terminated_at: '2026-10-20T02:00:03Z', termination_reason: '發送失敗：通道回應逾時', wake_condition: null,
    trace: [base.trace[0], base.trace[1], { ...base.trace[2], exited_at: null, out_port: null, decision_reason: '發送失敗。' }],
    delivery_history: [delivery(1, { status: 'FAILED', delivery_id: null })],
    ...over,
  })
}

/** 已中止：旅程 10/19 07:20Z（15:20 本地）被停止，停在 wait_1 */
export function aborted(over: Record<string, unknown> = {}) {
  return waiting({
    status: 'ABORTED', wake_condition: null, terminated_at: '2026-10-19T07:20:00Z', termination_reason: 'JOURNEY_STOPPED',
    ...over,
  })
}

export interface Opened { api: Api }
/** 直接進軌跡頁（深層連結）；預設 detail=waiting、定義 v3 */
export function openTrace(routes: Record<string, (c: Call) => Response | Promise<Response>> = {}, d: unknown = waiting()) {
  const api = backend({
    [`GET ${DETAIL}`]: () => json(d),
    [`GET ${VER}`]: () => json(journeyVersion()),
    ...routes,
  })
  start(`/enrollments/${EID}`)
  return api
}

export const heading = () => screen.findByRole('heading', { level: 1, name: /旅程的進度/ })

const mainEl = () => document.querySelector('main') as HTMLElement

/** 主要區文字：main 去掉所有 <details> */
export function mainText(): string {
  const clone = mainEl().cloneNode(true) as HTMLElement
  clone.querySelectorAll('details').forEach((n) => n.remove())
  return (clone.textContent ?? '').replace(/\s+/g, ' ')
}
export const bodyText = () => (document.body.textContent ?? '').replace(/\s+/g, ' ')

/** 摘要卡：role=status 且含三問的那一個 */
export function summaryCard(): HTMLElement {
  const el = screen.getAllByRole('status').find((s) => (s.textContent ?? '').includes('這位客戶現在在哪'))
  if (!el) throw new Error('找不到摘要卡（role=status，含「這位客戶現在在哪？」）')
  return el
}
export const cardText = () => (summaryCard().textContent ?? '').replace(/\s+/g, ' ')

/** 「走過的步驟」時間軸 <ol>（不含麵包屑） */
export async function timeline(): Promise<HTMLOListElement> {
  const h = await screen.findByRole('heading', { name: '走過的步驟' })
  let el: HTMLElement | null = h
  while (el && el !== document.body) {
    const ol = [...el.querySelectorAll('ol')].find((o) => !o.closest('nav'))
    if (ol) return ol as HTMLOListElement
    el = el.parentElement
  }
  throw new Error('找不到時間軸 <ol>')
}
export const stepItems = (ol: HTMLOListElement) => [...ol.querySelectorAll(':scope > li')] as HTMLElement[]
export const norm = (el: Element) => (el.textContent ?? '').replace(/\s+/g, ' ')

/** 進階資訊 <details> */
export function advanced(): HTMLDetailsElement {
  const el = [...document.querySelectorAll('details')].find((d) => (d.querySelector('summary')?.textContent ?? '').includes('進階資訊'))
  if (!el) throw new Error('找不到「進階資訊」<details>')
  return el as HTMLDetailsElement
}
export const advancedText = () => (advanced().textContent ?? '').replace(/\s+/g, ' ')

/** 主要區不可出現的技術字（D-079、D-080） */
export const FORBIDDEN: [string, RegExp][] = [
  ['Enrollment', /Enrollment/i],
  ['節點型別名', /(EventSource|WaitDelay|SendMessage|End|Start|Condition|SplitTest|WaitForEvent|Merge|Goal|Exit)Node/],
  ['UTC 原字串', /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/],
  ['UTC 字樣', /UTC/],
  ['進度編號', new RegExp(EID)],
  ['客戶 ID', new RegExp(PID)],
  ['旅程 ID', new RegExp(JID)],
  ['ISO 期間', /\bP\d+D\b|\bPT\d+[HMS]\b/],
  ['節點 id', /\b(src_1|wait_1|send_1|end_1)\b/],
  ['管道代碼', /MOCK|ch_mock_1/],
  ['來源類型', /CUSTOMER_BEHAVIOR/],
  ['as_of', /as_of/],
]

export function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}
