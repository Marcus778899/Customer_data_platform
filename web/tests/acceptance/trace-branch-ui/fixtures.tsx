/**
 * trace-branch-ui（FE-05）驗收測試共用 fixture（qa 撰寫，worker 不可改）。
 * 依 docs/contracts/journeys.md §5（node-stats）、journey-engine.md §4.5（軌跡推導）、docs/ui/journey-builder.md
 * 「節點人數與軌跡」與設計稿 st-view／st-states／tr-branch。
 *
 * 對 worker 的假設（規格沒寫死、測試必須定下來的，全部列在這裡）：
 *   - 進入點、fetch、polyfill、時區（Asia/Taipei）同 journey-customer-ui fixtures；旅程頁 `/journeys/<id>`（`?version=N` 切版本），
 *     軌跡頁 `/enrollments/<id>`。
 *   - 旅程頁每看一個版本就 `GET /api/v1/journeys/<id>/versions/<v>/node-stats`；沒有任何版本（尚未發佈）不打；節點圖與統計各自載入、互不阻擋。
 *   - 節點卡（`button[aria-pressed]`）有統計時，可見文字含「<人數> 人數」「<目前> 目前」，數字用千分位（1284 → 1,284，同設計稿）；
 *     aria-label 也含這兩個數字（讀屏用）。統計沒回來（載入中、失敗）時卡片不顯示「人數」。
 *   - 選取節點（點卡）→ 右側面板：`aside`（role=complementary，名稱＝節點名稱），h2＝節點名稱，列「人數（曾進入的客戶）」
 *     「進入次數（含迴圈）」「目前停在這裡」「離開時走的出口（人次）」與每個出口「<出口白話> → <目標節點名稱> <次數>」；
 *     出口白話：yes＝是、no＝否、occurred＝事件發生、timeout＝逾時；節點在迴圈裡（定義有迴圈）顯示「這個節點在迴圈裡」；
 *     關閉鈕 aria-label「關閉面板」，關閉後面板消失、卡片 aria-pressed=false。統計失敗時面板仍可開（只有設定）。
 *   - 載入中：統計區 `aria-busy="true"` 或 role=status 文字含「載入」；失敗：警示條（role=alert）文字含「各節點人數暫時取不到」
 *     「旅程圖與節點設定不受影響」與請求編號、按鈕「重試」（點了重打 node-stats）；尚無客戶（所有 entered 為 0）：文字含
 *     「這個版本還沒有客戶進入」「有客戶進入後，這裡會顯示每個節點的人數」，卡片仍顯示 0。
 *   - 旅程頁有「重新整理」按鈕（設計稿 st-view），按下重打 node-stats；顯示「資料時間」為 as_of 的本地時間 `YYYY-MM-DD HH:mm`
 *     與「重新整理才更新」字樣。
 *   - 迴圈邊（定義裡指回較前面節點的連線）在兩個頁面都出現文字「再等一輪」；旅程頁不疊軌跡（不出現「已經過」「目前位置」）。
 *   - 邊的 DOM：React Flow 邊容器 `[data-testid="rf__edge-<source>:<port>"]`（edge id 沿用 model.ts 的 `source:port`），
 *     其自身或內部元素帶 class `taken`（走過）／`skipped`（有軌跡但沒走）／`back`（回邊）。
 *   - 軌跡頁節點卡：目前所在節點為第 N 次進入時，卡片文字含「第 N 次」且仍含「目前位置」（既有驗收要求）；
 *     已離開且經過 2 次以上的節點含「第 M 次」或「M 次」；只經過 1 次的節點不含「次經過」「第 1 次」。
 *   - 軌跡頁時間軸（既有 `ol`，由舊到新）：同一節點出現 2 次以上時每列加「第 N 次經過」（N 依 §4.5 的 visit，從 1 起）；只出現 1 次的不加。
 */
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  EID, JID, backend, bodyText, deferred, enrollmentDetail, journeyDetail, json, problemWithId, resetEnv, start, teardown, traceEntry,
} from '../journey-customer-ui/fixtures'
import type { Call } from '../journey-customer-ui/fixtures'

export { EID, JID, backend, bodyText, deferred, journeyDetail, json, problemWithId, resetEnv, screen, start, teardown, userEvent, waitFor, within, traceEntry, enrollmentDetail }

export const API = `/api/v1/journeys/${JID}`
export const STATS3 = `${API}/versions/3/node-stats`
export const ENR = `/api/v1/enrollments/${EID}`

type N = { id: string; type: string; name: string | null; config: Record<string, unknown>; outputs: Record<string, string> }
const n = (id: string, type: string, name: string | null, outputs: Record<string, string>, config: Record<string, unknown> = {}): N => ({ id, type, name, config, outputs })

/** 設計稿的分支＋迴圈旅程（縮減版場景一）：是否 VIP 分流 → 合流 → 等結帳（逾時 → 再次提醒 → 回到等結帳） */
export function branchVersion(version = 3, over: Record<string, unknown> = {}) {
  return {
    journey_id: JID, name: '棄單挽回', version, description: null, settings: {}, entry_node: 'start', layout: null,
    published_at: '2026-10-18T01:12:00Z',
    nodes: [
      n('start', 'StartNode', '開始', { next: 'src_1' }),
      n('src_1', 'EventSourceNode', '加入購物車', { next: 'wait_1' }, { event_name: 'cart_item_added' }),
      n('wait_1', 'WaitDelayNode', '等 1 小時', { next: 'cond_vip' }, { duration: 'PT1H' }),
      n('cond_vip', 'ConditionNode', '是 VIP？', { yes: 'send_vip', no: 'send_normal' }),
      n('send_vip', 'SendMessageNode', 'VIP 專屬關心', { sent: 'merge_1' }, { template_id: 'cart_vip', channel_id: 'mock_default' }),
      n('send_normal', 'SendMessageNode', '一般提醒', { sent: 'merge_1' }, { template_id: 'cart_normal', channel_id: 'mock_default' }),
      n('merge_1', 'MergeNode', '合流', { next: 'wait_ev' }),
      n('wait_ev', 'WaitForEventNode', '等結帳 24h', { occurred: 'goal_1', timeout: 'send_last' }, { event_name: 'checkout_completed', timeout: 'PT24H' }),
      n('goal_1', 'GoalNode', '結帳完成', {}, { goal_id: 'g1' }),
      n('send_last', 'SendMessageNode', '再次提醒', { sent: 'wait_ev' }, { template_id: 'cart_last', channel_id: 'mock_default' }),
    ],
    ...over,
  }
}

export const stat = (node_id: string, entered: number, visits: number, current: number, out_ports: Record<string, number> = {}) =>
  ({ node_id, entered, visits, current, out_ports })
/** 契約 §5：items 依 nodes 順序、每個節點都列、StartNode 不列 */
export function nodeStats(version = 3, items?: unknown[]) {
  return {
    journey_id: JID, version, as_of: '2026-11-17T02:42:00Z',
    items: items ?? [
      stat('src_1', 1284, 1284, 0, { next: 1284 }),
      stat('wait_1', 1284, 1284, 86, { next: 1198 }),
      stat('cond_vip', 1198, 1198, 0, { yes: 311, no: 887 }),
      stat('send_vip', 311, 311, 0, { sent: 311 }),
      stat('send_normal', 887, 887, 0, { sent: 887 }),
      stat('merge_1', 1198, 1198, 0, { next: 1198 }),
      stat('wait_ev', 1198, 1401, 98, { occurred: 1100, timeout: 203 }),
      stat('goal_1', 1100, 1100, 0),
      stat('send_last', 203, 203, 0, { sent: 203 }),
    ],
  }
}
export const zeroStats = () =>
  nodeStats(3, ['src_1', 'wait_1', 'cond_vip', 'send_vip', 'send_normal', 'merge_1', 'wait_ev', 'goal_1', 'send_last'].map((id) => stat(id, 0, 0, 0)))

type Handler = (c: Call) => Response | Promise<Response>

export function openJourney(routes: Record<string, Handler> = {}, path = `/journeys/${JID}`, detail: Record<string, unknown> = {}) {
  const api = backend({
    [`GET ${API}`]: () => json(journeyDetail(detail)),
    [`GET ${API}/versions/3`]: () => json(branchVersion(3)),
    [`GET ${STATS3}`]: () => json(nodeStats()),
    ...routes,
  })
  start(path)
  return api
}

/** 節點卡（button；圖內元素用 hidden: true 才找得到） */
export const card = (name: RegExp | string) => screen.findByRole('button', { name, hidden: true })
export const announced = (el: Element) => `${el.getAttribute('aria-label') ?? ''} ${el.textContent ?? ''}`.replace(/\s+/g, ' ')
export const norm = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim()

/** 軌跡：非 VIP 走「否」→ 合流 → 等結帳逾時 → 再次提醒 → 第 2 次回到等結帳（進行中） */
export function loopEnrollment(over: Record<string, unknown> = {}) {
  return enrollmentDetail({
    journey_version: 3, status: 'WAITING', current_node_id: 'wait_ev', current_node_type: 'WaitForEventNode', current_node_name: '等結帳 24h',
    trace: [
      traceEntry(1, 'src_1', 'EventSourceNode', '加入購物車'),
      traceEntry(2, 'wait_1', 'WaitDelayNode', '等 1 小時'),
      traceEntry(3, 'cond_vip', 'ConditionNode', '是 VIP？', { out_port: 'no', decision_reason: '會員等級不是 VIP。' }),
      traceEntry(4, 'send_normal', 'SendMessageNode', '一般提醒', { out_port: 'sent' }),
      traceEntry(5, 'merge_1', 'MergeNode', '合流'),
      traceEntry(6, 'wait_ev', 'WaitForEventNode', '等結帳 24h', { out_port: 'timeout', decision_reason: '逾時沒等到。' }),
      traceEntry(7, 'send_last', 'SendMessageNode', '再次提醒', { out_port: 'sent' }),
      traceEntry(8, 'wait_ev', 'WaitForEventNode', '等結帳 24h', { exited_at: null, out_port: null, decision_reason: '再等一輪。' }),
    ],
    ...over,
  })
}

export function openTrace(detail: Record<string, unknown> = loopEnrollment(), version: unknown = branchVersion(3)) {
  const api = backend({
    [`GET ${ENR}`]: () => json(detail),
    [`GET ${API}/versions/3`]: () => json(version),
  })
  start(`/enrollments/${EID}`)
  return api
}

/** 邊容器（React Flow 的 data-testid）；自身或內部元素帶指定 class */
export const edgeHas = (id: string, cls: 'taken' | 'skipped' | 'back') => {
  const w = document.querySelector(`[data-testid="rf__edge-${id}"]`)
  return !!w && (w.classList.contains(cls) || !!w.querySelector(`.${cls}`))
}
export const edgesRendered = () => !!document.querySelector('[data-testid^="rf__edge-"]')
