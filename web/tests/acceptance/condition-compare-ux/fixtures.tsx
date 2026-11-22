/**
 * condition-compare-ux 驗收測試共用 fixture（qa 撰寫，worker 不可改）。
 * 依 docs/ui/canvas-interaction.md §3、設計稿 docs/ui/canvas-interaction/index.html（條件面板與試試看）、契約 journeys.md §4／§4.1。
 *
 * 對 worker 的假設（規格沒寫死、測試必須定下來的）：
 *   - 比較 option 文字（完整、全形括號）：大於（>）至少（≥）小於（<）至多（≤）等於 不等於 包含 是其中之一 不是其中任何一個 有填 沒填；
 *     分組 optgroup label：「數字比大小」「比對內容」「有沒有填」。
 *   - 句子預覽：條件面板（ConditionNode）最上方（欄位列之前）一段文字，如「客戶要全部符合：『購物車金額』大於 500；『會員等級』等於 gold。」
 *   - 試試看：條件面板底部的 <details>，summary 文字含「試試看」，預設 open；內含
 *       · 「一位會符合的客戶」「一位不會符合的客戶」兩塊範例（逐欄列值；不會符合那位只一條標「不合：要…」）；
 *       · 每個條件欄位一個樣本輸入（textbox，accessible name 含欄位顯示名，例「會員等級」）；
 *       · role=status：「這位客戶會符合」或「這位客戶不會符合」＋逐條結果 li（含欄位顯示名）；
 *       · 固定說明（含「不會讀取真實客戶」）。
 *   - 樣本文字轉型：空白 = 欄位不存在（缺值）；條件的 value 是 number 時，數字樣子的文字當 number、否則當字串；value 是字串或陣列時樣本一律字串。
 *     （因此契約 §4.1 中需要「字串 600 對 number 500」「陣列」「空字串」等 UI 打不出來的向量，由 worker 的單元測試逐條跑，
 *      這裡只驗 UI 能表達的向量。）
 *   - 有 behavior／segment 葉節點時，試試看區所有輸入停用並說明含「不支援」。
 *   - 試算純前端：打樣本不產生任何 API 請求。
 */
import { expect } from 'vitest'
import { screen, waitFor, within, userEvent, editorBackend, openEditor, selectNode, smallDef } from '../journey-canvas-ui/fixtures'
import type { Def } from '../journey-canvas-ui/fixtures'
export * from '../journey-canvas-ui/fixtures'

export const tierLeaf = (operator: string, value?: unknown) =>
  value === undefined ? { type: 'attribute', field: 'tier', operator } : { type: 'attribute', field: 'tier', operator, value }
export const cartLeaf = (operator: string, value?: unknown) =>
  value === undefined
    ? { type: 'attribute', field: 'trigger.properties.cart_total', operator }
    : { type: 'attribute', field: 'trigger.properties.cart_total', operator, value }

export function condDef(condition: unknown): Def {
  const d = smallDef()
  d.nodes = d.nodes.map((n) => (n.id === 'cond_vip' ? { ...n, config: { condition } } : n))
  return d
}
export const andOf = (...children: unknown[]) => ({ op: 'AND', children })

export async function openCond(condition: unknown) {
  const ed = editorBackend({ def: condDef(condition) })
  await openEditor()
  await selectNode('是 VIP？')
  const panel = await screen.findByRole('complementary', { name: /是 VIP？/ })
  await waitFor(() => expect(within(panel).getAllByLabelText('欄位').length).toBeGreaterThan(0))
  return { ed, panel }
}

export const tryBox = (panel: HTMLElement): HTMLDetailsElement => {
  const hit = [...panel.querySelectorAll('details')].find((d) => (d.querySelector('summary')?.textContent ?? '').includes('試試看'))
  if (!hit) throw new Error('面板裡沒有「試試看」區（details）')
  return hit as HTMLDetailsElement
}
export const sampleBox = (box: HTMLElement, fieldName: string) => within(box).getByRole('textbox', { name: new RegExp(fieldName) }) as HTMLInputElement
export async function setSample(box: HTMLElement, fieldName: string, text: string) {
  const el = sampleBox(box, fieldName)
  await userEvent.clear(el)
  if (text) await userEvent.type(el, text)
}
export const verdict = (box: HTMLElement) => within(box).getAllByRole('status').map((s) => s.textContent ?? '').join(' ')
export const rowLabel = (sel: HTMLElement) => [...(sel as HTMLSelectElement).options].filter((o) => o.value !== '').map((o) => (o.textContent ?? '').trim())
export const groupsOf = (sel: HTMLElement) =>
  [...sel.querySelectorAll('optgroup')].map((g) => ({ label: g.label, values: [...g.querySelectorAll('option')].map((o) => (o as HTMLOptionElement).value) }))
