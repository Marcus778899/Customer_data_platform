/**
 * journey-customer-ui（FE-03／QA-05a）驗收測試共用 fixture（qa 撰寫，worker 不可改）。
 * 後端一律依契約 mock：journeys.md §1～§2、profiles.md §1～§3、journey-engine.md §4、§4.4、§4.5。
 * UI 文案與結構依 docs/ui/journey-platform.md 與設計稿 index.html。
 *
 * 對 worker 的假設（全部列在這裡，個別測試檔不重複）：
 *   - 進入點 `web/src/App.tsx` 具名匯出 `App`；請求走全域 fetch、相對路徑 /api/v1/...（同 shell/helpers.tsx）。
 *   - 路由網址規格沒定，除了 ROUTES 列的之外一律不假設：測試從外框導覽（「旅程」「客戶」）與頁面上的連結點進去；
 *     要驗證「網址同步」時，記下當下 location 再用它重新載入。
 *   - ROUTES.browse（最近 Enrollment 瀏覽）是唯一寫死的網址，見其註解。
 *   - 時區固定 Asia/Taipei（UTC+8），時間格式沿用 web/src/format.ts：`YYYY-MM-DD HH:mm`。
 *   - jsdom 沒有 ResizeObserver、DOMMatrixReadOnly（@xyflow/react 需要），在此補最小 polyfill；不影響行為斷言。
 */
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  AMY, json, mockApi, noContent, problem, resetEnv, start, teardown,
  type Api, type Call,
} from '../shell/helpers'

export { AMY, json, mockApi, noContent, problem, resetEnv, start, teardown }
export type { Api, Call }

process.env.TZ = 'Asia/Taipei'

/**
 * 唯一寫死的網址：「最近 Enrollment 瀏覽」頁。
 * 假設：`/` 在 v0.2.0 是旅程列表（外框導覽「旅程」），舊的 Enrollment 精確查詢頁（v0.1.0）移到 `/enrollments`，
 * 該頁 `q` 省略 = 瀏覽最近的 Enrollment（journey-engine §4、D-030）。若實作放別處，請 manager 改這一行。
 */
export const ROUTES = { browse: '/enrollments' }

export function installPolyfills() {
  const g = globalThis as Record<string, unknown>
  if (!g.ResizeObserver) {
    g.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
  }
  if (!g.DOMMatrixReadOnly) {
    g.DOMMatrixReadOnly = class {
      m22 = 1
    }
  }
  if (!g.IntersectionObserver) {
    g.IntersectionObserver = class { observe() {} unobserve() {} disconnect() {} takeRecords() { return [] } }
  }
}

export interface Deferred<T> { promise: Promise<T>; resolve: (v: T) => void }
export function deferred<T>(): Deferred<T> {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

export const page = <T,>(items: T[], next_cursor: string | null = null) => ({ items, next_cursor })
export const params = (c: Call) => new URLSearchParams(c.search)
export const problemWithId = (status: number, code: string, request_id: string) =>
  json({ type: 'about:blank', title: 'x', status, code, detail: 'd', request_id }, status)

/** 登入後的後端（預設 EDITOR 陳小美）；routes 以 "METHOD /api/v1/路徑" 為鍵，未列的 GET 回空清單 */
export function backend(routes: Record<string, (c: Call) => Response | Promise<Response>> = {}, me = AMY): Api {
  installPolyfills()
  return mockApi({ me, routes })
}

/** 外框導覽（桌面側欄那份；jsdom 不套 CSS，窄螢幕選單另有一份 aria-label「主要導覽（窄螢幕）」） */
export async function gotoNav(label: '旅程' | '客戶') {
  const nav = await screen.findByRole('navigation', { name: '主要導覽' })
  await userEvent.click(within(nav).getByRole('link', { name: label }))
}

export const bodyText = () => (document.body.textContent ?? '').replace(/\s+/g, ' ')

// ---------- 資料 builder（欄位名即契約欄位） ----------
export const JID = '01JCXYZJOURNEY0000000001'
export const PID = '01JCX7K2M4N8P3Q6R9S5T1V0WZ'
export const EID = '01JD3A5B7C9D1E3F5G7H9J2K4M'

export function journeySummary(over: Record<string, unknown> = {}) {
  return {
    journey_id: JID, name: '棄單挽回', status: 'ACTIVE', latest_version: 3, has_draft: false, review_status: null,
    updated_at: '2026-10-18T01:12:00Z', published_at: '2026-10-18T01:12:00Z', ...over,
  }
}
export function journeyDetail(over: Record<string, unknown> = {}) {
  return {
    ...journeySummary(), description: '加入購物車未結帳的挽回流程',
    versions: [
      { version: 3, published_at: '2026-10-18T01:12:00Z' },
      { version: 2, published_at: '2026-10-12T03:05:00Z' },
      { version: 1, published_at: '2026-10-01T02:00:00Z' },
    ],
    stopped_at: null, stop_reason: null, stopped_by: null, ...over,
  }
}
/** 直線旅程：開始 → 加入購物車 → 等 N 天 → 提醒信 → 結束（spec 03 §4；StartNode 不寫軌跡） */
export function journeyVersion(version = 3, waitName = '等 3 天', over: Record<string, unknown> = {}) {
  return {
    journey_id: JID, name: '棄單挽回', version, description: null, settings: {}, entry_node: 'start',
    nodes: [
      { id: 'start', type: 'StartNode', name: '開始', config: {}, outputs: { next: 'src_1' } },
      { id: 'src_1', type: 'EventSourceNode', name: '加入購物車', config: { event_name: 'cart_item_added' }, outputs: { next: 'wait_1' } },
      { id: 'wait_1', type: 'WaitDelayNode', name: waitName, config: { duration: 'P3D' }, outputs: { next: 'send_1' } },
      { id: 'send_1', type: 'SendMessageNode', name: '提醒信', config: { template_id: 'tpl_cart_reminder', channel_id: 'ch_email' }, outputs: { sent: 'end_1' } },
      { id: 'end_1', type: 'EndNode', name: '結束', config: {}, outputs: {} },
    ],
    layout: null, published_at: '2026-10-18T01:12:00Z', ...over,
  }
}

export function enrollmentSummary(over: Record<string, unknown> = {}) {
  return {
    enrollment_id: EID, journey_id: JID, profile_id: PID, current_node_id: 'wait_1', current_node_type: 'WaitDelayNode',
    journey_version: 3, journey_name: '棄單挽回', current_node_name: '等 3 天', status: 'WAITING',
    enrolled_at: '2026-10-18T02:00:00Z', updated_at: '2026-10-18T02:00:05Z', terminated_at: null, ...over,
  }
}
export function traceEntry(seq: number, node_id: string, node_type: string, node_name: string | null, over: Record<string, unknown> = {}) {
  return {
    seq, node_id, node_type, node_name, entered_at: '2026-10-18T02:00:00Z', exited_at: '2026-10-18T02:00:00Z', out_port: 'next',
    decision_reason: `原因-${node_id}`, evaluated: {}, ...over,
  }
}
/** 進行中：走過 src_1，停在 wait_1 */
export function enrollmentDetail(over: Record<string, unknown> = {}) {
  return {
    ...enrollmentSummary(), termination_reason: null,
    wake_condition: { type: 'TIMER', wake_at: '2026-10-21T02:00:00Z', event_name: null, signal_name: null, timeout_at: null },
    variables: {},
    trigger_snapshot: { event_id: 'ev1', event_name: 'cart_item_added', source_type: 'CUSTOMER_BEHAVIOR', occurred_at: '2026-10-18T01:59:41Z', properties: {}, profile_snapshot: {} },
    trace: [
      traceEntry(1, 'src_1', 'EventSourceNode', '加入購物車', { decision_reason: '收到事件 cart_item_added，建立 Enrollment。', evaluated: { event_id: 'ev-plain-1' } }),
      traceEntry(2, 'wait_1', 'WaitDelayNode', '等 3 天', { exited_at: null, out_port: null, decision_reason: '等待 P3D，預計 2026-10-21T02:00:00Z 喚醒。', evaluated: { duration: 'P3D' } }),
    ],
    delivery_history: [], delivery_history_next_cursor: null, as_of: '2026-10-18T03:05:12Z', masked_paths: [], ...over,
  }
}
/** 已完成（終態）：全部節點走過 */
export function enrollmentDone(over: Record<string, unknown> = {}) {
  return enrollmentDetail({
    status: 'COMPLETED', current_node_id: 'end_1', current_node_type: 'EndNode', current_node_name: '結束',
    terminated_at: '2026-10-21T02:00:03Z', termination_reason: '旅程走到結束節點，Enrollment 完成。', wake_condition: null,
    trace: [
      traceEntry(1, 'src_1', 'EventSourceNode', '加入購物車'),
      traceEntry(2, 'wait_1', 'WaitDelayNode', '等 3 天', { exited_at: '2026-10-21T02:00:02Z' }),
      traceEntry(3, 'send_1', 'SendMessageNode', '提醒信', { out_port: 'sent', decision_reason: '已送出 1 則。' }),
      traceEntry(4, 'end_1', 'EndNode', '結束', { out_port: null, decision_reason: '旅程走到結束節點。' }),
    ],
    ...over,
  })
}

export function profileSummary(over: Record<string, unknown> = {}) {
  return {
    profile_id: PID, matched_by: 'EMAIL',
    identifiers: [
      { type: 'EMAIL', value: 'a***@example.com', masked: true },
      { type: 'MEMBER_ID', value: 'M-20418', masked: false },
    ],
    created_at: '2026-05-03T03:20:00Z', updated_at: '2026-11-01T08:00:00Z', ...over,
  }
}
export function profileDetail(over: Record<string, unknown> = {}) {
  const { matched_by: _m, ...base } = profileSummary()
  void _m
  return {
    ...base,
    attributes: { tier: 'regular', birthday: '***', city: '***' },
    masked_paths: ['attributes.birthday', 'attributes.city', 'identifiers[0].value'],
    as_of: '2026-11-01T08:05:12Z', ...over,
  }
}
