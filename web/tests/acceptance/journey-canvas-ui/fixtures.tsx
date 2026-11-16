/**
 * journey-canvas-ui（FE-04）驗收測試共用 fixture（qa 撰寫，worker 不可改）。
 * 後端依 docs/contracts/journeys.md §3（草稿、validate）、§4（field-keys）mock；UI 文案與結構依
 * docs/ui/journey-builder.md 與設計稿 index.html 的 ed-* 畫面（aria-label、按鈕文字照設計稿）。
 *
 * 對 worker 的假設（規格沒寫死、測試必須定下來的，全部列在這裡）：
 *   - 進入點 `web/src/App.tsx` 具名匯出 `App`；請求走全域 fetch、相對路徑 /api/v1/...。
 *   - 路由：編輯畫布 = `/journeys/<journey_id>/edit`；新建草稿 = `/journeys/new`
 *     （畫面：輸入框「旅程名稱」＋按鈕「建立草稿」→ POST /journeys/drafts → 導向該旅程的編輯網址）。
 *     旅程列表／旅程頁的入口按鈕不在本單負責檔案內，不測。
 *   - 儲存 = 自動儲存（設計稿狀態列「已自動儲存」「尚未儲存」），PUT 帶 `{ revision, definition, layout }`；
 *     防抖秒數不假設，測試以 waitFor（最長 8 秒）等 PUT。
 *   - 節點是畫布上的 `<button aria-pressed>`，名稱含節點名稱；選取後右側面板（aside，aria-labelledby 指向節點名稱標題）
 *     出現，欄位標籤同設計稿（名稱、管道代碼、範本代碼、時間／單位、等這個事件、最多等／單位／不設時限、
 *     下一步各出口：送出後、等完之後、事件發生時、逾時沒等到、是、否、會合後）。
 *   - 條件列：select/input 的 aria-label 為「欄位」「比較」「值」，option 的 value 是原始欄位代碼／運算子（EQ…）。
 *   - jsdom 沒有 ResizeObserver、DOMMatrixReadOnly（@xyflow/react 需要），由 journey-customer-ui/fixtures 補 polyfill；時區 Asia/Taipei。
 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect } from 'vitest'
import { AMY, backend, bodyText, deferred, journeyDetail, json, problem, resetEnv, start, teardown } from '../journey-customer-ui/fixtures'
import { user } from '../shell/helpers'
import type { Call } from '../shell/helpers'

export { AMY, backend, bodyText, deferred, journeyDetail, json, problem, resetEnv, start, teardown, user, cleanup, screen, waitFor, within, userEvent }
export type { Call }

export const JID = '01JCXYZJOURNEY0000000001'
export const EDIT = `/journeys/${JID}/edit`
export const DRAFT_PATH = `/api/v1/journeys/${JID}/draft`

export interface Def {
  name: string
  description: string | null
  settings: Record<string, unknown>
  entry_node: string
  nodes: Array<{ id: string; type: string; name: string | null; config: Record<string, unknown>; outputs: Record<string, unknown> }>
}
export const skeleton = (name = '新旅程'): Def => ({
  name, description: null, settings: {}, entry_node: 'start',
  nodes: [{ id: 'start', type: 'StartNode', name: null, config: {}, outputs: {} }],
})
/** 有進入事件、等待、條件（「否」未連線）、訊息、結束的小旅程 */
export const smallDef = (): Def => ({
  name: '棄單挽回', description: null, settings: {}, entry_node: 'start',
  nodes: [
    { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'src_1' } },
    { id: 'src_1', type: 'EventSourceNode', name: '加入購物車', config: { event_name: 'cart_item_added' }, outputs: { next: 'wait_1' } },
    { id: 'wait_1', type: 'WaitDelayNode', name: '等 1 小時', config: { duration: 'PT1H' }, outputs: { next: 'cond_vip' } },
    {
      id: 'cond_vip', type: 'ConditionNode', name: '是 VIP？',
      config: { condition: { op: 'AND', children: [{ type: 'attribute', field: 'tier', operator: 'EQ', value: 'VIP' }] } },
      outputs: { yes: 'send_vip' },
    },
    { id: 'send_vip', type: 'SendMessageNode', name: 'VIP 專屬關心', config: { channel_id: 'mock_default', template_id: 'cart_vip' }, outputs: { sent: 'end_1' } },
    { id: 'end_1', type: 'EndNode', name: '流程結束', config: {}, outputs: {} },
  ],
})
/** 有迴圈：等結帳 24h →（逾時）再次提醒 → 回到等結帳 24h */
export const loopDef = (): Def => ({
  name: '棄單挽回', description: null, settings: {}, entry_node: 'start',
  nodes: [
    { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'src_1' } },
    { id: 'src_1', type: 'EventSourceNode', name: '加入購物車', config: { event_name: 'cart_item_added' }, outputs: { next: 'wait_ev' } },
    { id: 'wait_ev', type: 'WaitForEventNode', name: '等結帳 24h', config: { event_name: 'order_completed', timeout: 'P1D' }, outputs: { occurred: 'goal_1', timeout: 'send_again' } },
    { id: 'goal_1', type: 'GoalNode', name: '結帳完成', config: { goal_id: 'purchase' }, outputs: {} },
    { id: 'send_again', type: 'SendMessageNode', name: '再次提醒', config: { channel_id: 'mock_default', template_id: 'cart_last' }, outputs: { sent: 'wait_ev' } },
  ],
})

export const ref = (id: string, name: string) => ({ user_id: id, display_name: name })
export function draftBody(over: Record<string, unknown> = {}) {
  return {
    journey_id: JID, revision: 4, definition: smallDef(), layout: null,
    updated_at: '2026-11-02T01:41:00Z', updated_by: ref('01JD0USER0000000000000001', '陳小美'), latest_review: null, ...over,
  }
}

export interface PutBody {
  revision: number
  definition: Def
  layout: { nodes: Record<string, { x: number; y: number }> } | null
}
export interface Editor {
  api: ReturnType<typeof backend>
  state: { revision: number; definition: Def; layout: unknown }
  puts: () => Call[]
  lastPut: () => PutBody
}

/**
 * 有狀態的草稿後端：GET 回目前內容；PUT 的 revision 必須等於目前值（否則 409 DRAFT_CONFLICT），成功則 +1 並存下內容。
 * routes 可覆寫任何端點（例如讓 PUT 回 409 DRAFT_IN_REVIEW、5xx）。
 */
export function editorBackend(opts: {
  def?: Def; revision?: number; layout?: unknown; me?: ReturnType<typeof user>
  routes?: Record<string, (c: Call) => Response | Promise<Response>>
  detail?: Record<string, unknown>
} = {}): Editor {
  const state = { revision: opts.revision ?? 4, definition: opts.def ?? smallDef(), layout: (opts.layout ?? null) as unknown }
  const draft = () => draftBody({ revision: state.revision, definition: state.definition, layout: state.layout })
  const api = backend({
    [`GET ${DRAFT_PATH}`]: () => json(draft()),
    [`PUT ${DRAFT_PATH}`]: (c) => {
      const b = c.body as PutBody
      if (b.revision !== state.revision) return problem(409, 'DRAFT_CONFLICT')
      state.revision += 1
      state.definition = b.definition
      state.layout = b.layout
      return json(draft())
    },
    [`GET /api/v1/journeys/${JID}`]: () => json(journeyDetail({ journey_id: JID, has_draft: true, ...opts.detail })),
    [`POST ${DRAFT_PATH}/validate`]: () => json({ valid: true, errors: [], warnings: [] }),
    'GET /api/v1/journeys/field-keys': () =>
      json({ profile_attributes: ['city', 'tier'], event_properties: ['cart_id', 'cart_total'], as_of: '2026-11-02T01:41:00Z' }),
    ...opts.routes,
  }, opts.me ?? AMY)
  const puts = () => api.calls.filter((c) => c.method === 'PUT' && c.path === DRAFT_PATH)
  return { api, state, puts, lastPut: () => puts().at(-1)!.body as PutBody }
}

export async function openEditor(path = EDIT) {
  start(path)
  return screen.findByRole('toolbar', { name: '加入節點' })
}

/** 畫布上的節點按鈕（排除工具列、面板、窄螢幕選單；條件的「全部符合」切換鈕在面板內） */
export const nodeButtons = () =>
  [...document.querySelectorAll<HTMLButtonElement>('button[aria-pressed]')].filter(
    (b) => !b.closest('[role="toolbar"], aside, details, [role="dialog"], [role="complementary"]'),
  )
export const tryNodeButton = (name: string) => nodeButtons().find((b) => (b.textContent ?? '').includes(name))
export const nodeButton = (name: string) => {
  const hit = tryNodeButton(name)
  if (!hit) throw new Error(`畫布上找不到節點「${name}」；目前節點：${nodeButtons().map((b) => b.textContent).join('｜')}`)
  return hit
}

export async function selectNode(name: string) {
  const b = await waitFor(() => nodeButton(name))
  if (b.getAttribute('aria-pressed') !== 'true') await userEvent.click(b)
  await waitFor(() => expect(nodeButton(name).getAttribute('aria-pressed')).toBe('true'))
}

/** 工具列加節點：按下後把新出現的節點按鈕選起來，回傳它 */
export async function addNode(label: string) {
  const before = nodeButtons()
  const tb = screen.getByRole('toolbar', { name: '加入節點' })
  await userEvent.click(within(tb).getByRole('button', { name: label }))
  const added = await waitFor(() => {
    const now = nodeButtons()
    const fresh = now.filter((b) => !before.includes(b))
    const pick = fresh.length ? fresh : now.length > before.length ? [now[now.length - 1]] : []
    expect(pick.length, `按下「${label}」後畫布沒有新節點`).toBeGreaterThan(0)
    return pick[pick.length - 1]
  })
  if (added.getAttribute('aria-pressed') !== 'true') await userEvent.click(added)
  await screen.findByLabelText('名稱')
  return added
}

export async function rename(to: string) {
  const box = screen.getByLabelText('名稱')
  await userEvent.clear(box)
  await userEvent.type(box, to)
}
export async function typeInto(label: string | RegExp, value: string) {
  const box = screen.getByLabelText(label)
  await userEvent.clear(box)
  await userEvent.type(box, value)
}
/** 選 <select> 的選項：先比 value，再比顯示文字；找不到就丟錯並列出選項 */
export async function choose(select: HTMLElement, wanted: string | string[]) {
  const list = Array.isArray(wanted) ? wanted : [wanted]
  const opts = [...(select as HTMLSelectElement).options]
  const hit =
    opts.find((o) => list.includes(o.value)) ??
    opts.find((o) => list.some((w) => (o.textContent ?? '').trim() === w)) ??
    opts.find((o) => list.some((w) => (o.textContent ?? '').includes(w)))
  if (!hit) throw new Error(`select 沒有選項 ${list.join('／')}；現有：${opts.map((o) => `${o.value}|${o.textContent}`).join('，')}`)
  await userEvent.selectOptions(select, hit.value)
}
export const chooseLabel = (label: string | RegExp, wanted: string | string[]) => choose(screen.getByLabelText(label), wanted)

export async function waitForPut(pred: (b: PutBody) => boolean, ed: Editor, timeout = 8000) {
  const hits = () => ed.puts().map((c) => c.body as PutBody).filter(pred)
  await waitFor(() => expect(hits().length, `沒有符合條件的 PUT（共 ${ed.puts().length} 次）`).toBeGreaterThan(0), { timeout })
  return hits().at(-1)!
}
export const nodeById = (def: Def, id: string) => def.nodes.find((n) => n.id === id)
export const byType = (def: Def, type: string) => def.nodes.filter((n) => n.type === type)
export const byName = (def: Def, name: string) => def.nodes.find((n) => n.name === name)

export const NINE = ['訊息', '等待', '等事件', '條件', 'A/B 分流', '合流', '達標', '退出', '結束'] as const
