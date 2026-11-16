/**
 * event-filter-canvas-ui 驗收測試共用 fixture（qa 撰寫，worker 不可改）。
 * 依 docs/contracts/journeys.md §4、docs/ui/journey-builder.md「事件篩選」與設計稿 ef-source／ef-states／ef-errors；
 * 其餘進入點、fetch、時區、polyfill、節點選取方式同 journey-canvas-ui/fixtures。
 *
 * 對 worker 的假設（規格沒寫死、測試必須定下來的，全部列在這裡）：
 *   - 篩選區塊是面板內的 fieldset（role=group），legend：進入條件「只讓符合條件的事件進入（選填）」、等事件「只算符合條件的事件（選填）」。
 *   - 空狀態：標題「目前沒有篩選」＋按鈕「加入第一個條件」；有條件後用「加入條件」「加入群組」「清除篩選」；每個條件有 aria-label「移除這個條件」。
 *   - 條件列沿用條件節點：select「欄位」、select「比較」、input「值」，一列在 li／.cond-row／.leaf 內。
 *     欄位 option 的 value 是完整路徑 `properties.<鍵>`（顯示文字用內建白話名，cart_total=購物車金額、order_total=訂單金額、
 *     沒有對照的顯示原代碼）；最後一個 option 文字「手動輸入欄位代碼…」，選了之後出現文字框「欄位代碼」（`properties.` 前綴固定，只填鍵）。
 *   - 比較 option 的 value 是運算子（GT…）。cart_total／order_total／item_count 視為數字欄：GT／GTE／LT／LTE 送 number；
 *     coupon_code 等文字欄的 EQ／NEQ／CONTAINS 送 string，IN／NOT_IN 的值以逗號分隔、送去除空白的 string 陣列。
 *   - 「全部符合／任一符合」是 role=group「組合方式」內的兩個 aria-pressed 按鈕，對應 op AND／OR。
 *   - 一句摘要在 role=status（aria-live）；清除後出現「已清除 N 項條件」＋按鈕「復原」。
 *   - 條件沒填完（缺欄位／比較／值或值格式錯）不寫入 PUT：那一個葉節點不出現在 event_filter；
 *     若整棵篩選沒有任何完整條件，PUT 的 event_filter 為 null 或省略。
 *   - 欄位建議載入中：role=status aria-label「正在載入欄位建議」；失敗：警告「欄位建議暫時載不到」＋按鈕「重試」（3 秒逾時不測）。
 *   - 驗證錯誤：按「驗證」後，依 Issue.field 路徑把訊息顯示在對應那一列（值欄／欄位欄 aria-invalid），整個篩選層級的錯誤顯示在篩選區塊內。
 */
import { expect } from 'vitest'
import {
  cleanup, screen, waitFor, within, userEvent, editorBackend, openEditor, selectNode, waitForPut, choose, json,
} from '../journey-canvas-ui/fixtures'
import type { Call, Def, Editor, PutBody } from '../journey-canvas-ui/fixtures'

export * from '../journey-canvas-ui/fixtures'
export { cleanup, screen, waitFor, within, userEvent }

export type Filter = { op: string; children: unknown[] } | null
export const leaf = (field: string, operator: string, value?: unknown) =>
  value === undefined ? { type: 'attribute', field, operator } : { type: 'attribute', field, operator, value }
export const and = (...children: unknown[]) => ({ op: 'AND', children })

export const efDef = (srcFilter?: Filter, waitFilter?: Filter, srcEvent = 'add_to_cart'): Def => ({
  name: '棄單挽回', description: null, settings: {}, entry_node: 'start',
  nodes: [
    { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'src_1' } },
    { id: 'src_1', type: 'EventSourceNode', name: '加入購物車', config: { event_name: srcEvent, ...(srcFilter ? { event_filter: srcFilter } : {}) }, outputs: { next: 'wait_ev' } },
    {
      id: 'wait_ev', type: 'WaitForEventNode', name: '等結帳 24h',
      config: { event_name: 'order_completed', timeout: 'PT24H', ...(waitFilter ? { event_filter: waitFilter } : {}) },
      outputs: { occurred: 'goal_1', timeout: 'end_1' },
    },
    { id: 'goal_1', type: 'GoalNode', name: '結帳完成', config: { goal_id: 'purchase' }, outputs: {} },
    { id: 'end_1', type: 'EndNode', name: '流程結束', config: {}, outputs: {} },
  ],
})

export const FIELD_KEYS = ['cart_total', 'coupon_code', 'item_count', 'order_total', 'utm_source']
export const SRC_LEGEND = /^只讓符合條件的事件進入/
export const WAIT_LEGEND = /^只算符合條件的事件/
export const FK = '/api/v1/journeys/field-keys'
export const fieldKeysBody = () => json({ profile_attributes: ['tier'], event_properties: FIELD_KEYS, as_of: '2026-11-02T01:41:00Z' })

export function efBackend(
  def: Def = efDef(),
  routes: Record<string, (c: Call) => Response | Promise<Response>> = {},
  extra: { detail?: Record<string, unknown> } = {},
): Editor {
  return editorBackend({ def, ...extra, routes: { [`GET ${FK}`]: fieldKeysBody, ...routes } })
}

export async function open(node: string, legend: RegExp = SRC_LEGEND) {
  await openEditor()
  await selectNode(node)
  return waitFor(() => screen.getByRole('group', { name: legend }))
}

export const rowsOf = (group: HTMLElement) =>
  [...group.querySelectorAll<HTMLSelectElement>('select[aria-label="欄位"]')].map(
    (s) => s.closest('li, .cond-row, .leaf') as HTMLElement,
  )

export const optionCount = (sel: HTMLElement) => (sel as HTMLSelectElement).options.length

/** 在篩選區塊新增一條完成的條件；回傳該列 */
export async function addCond(group: HTMLElement, field: string | string[], op: string | string[], value?: string) {
  const first = within(group).queryByRole('button', { name: '加入第一個條件' })
  await userEvent.click(first ?? within(group).getByRole('button', { name: '加入條件' }))
  const row = await waitFor(() => {
    const r = rowsOf(group).at(-1)
    expect(r, '按下新增後沒有出現條件列').toBeTruthy()
    return r!
  })
  await waitFor(() => expect(optionCount(within(row).getByLabelText('欄位'))).toBeGreaterThan(2))
  await choose(within(row).getByLabelText('欄位'), field)
  await choose(within(row).getByLabelText('比較'), op)
  if (value !== undefined) {
    const box = within(row).getByLabelText('值')
    await userEvent.clear(box)
    await userEvent.type(box, value)
  }
  return row
}

export const filterOf = (b: PutBody, id: string): Filter =>
  (b.definition.nodes.find((n) => n.id === id)!.config as { event_filter?: Filter }).event_filter ?? null
export const putWith = (ed: Editor, id: string, pred: (f: Filter) => boolean) =>
  waitForPut((b) => pred(filterOf(b, id)), ed)
export const statusText = (g: HTMLElement) => within(g).getAllByRole('status').map((s) => s.textContent).join(' ')
