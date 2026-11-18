// @vitest-environment jsdom
/**
 * canvas-zoom-filter-fix：F-33 手動輸入／型別不明欄位可設數字比較（docs/ui/journey-builder.md「事件篩選」比較選項、值與型別、數字驗證錯誤；
 * 契約 docs/contracts/journeys.md §4「比較語意」依 value 的 JSON 型別）。事件篩選與條件節點同一套。
 * 對 worker 的假設（規格沒寫死）：
 *   - 比較下拉的選項依 value（GT…）判斷；型別不明的欄位用 optgroup 分三組，label：
 *     「比大小（值會存成數字）」「比對內容」「有沒有填」。
 *   - 內建型別對照至少含 cart_total、price、quantity 三個數字欄：只列 GT／GTE／LT／LTE／EQ／NEQ／IS_NULL／IS_NOT_NULL，
 *     不列 CONTAINS／IN／NOT_IN，也不顯示「值當成」切換。`amount` 不在對照內（型別不明）。
 *   - 「值當成」切換：role=group（或 radiogroup）名稱含「值當成」，內有名稱「文字」「數字」的 radio 或 button。
 *     只在型別不明欄位的 EQ／NEQ／IN／NOT_IN 顯示；預設文字。
 *   - 數字驗證錯誤顯示在該條件列內 role=alert，值欄 aria-invalid=true；訊息含「要填數字」、「第 N 項『…』不是數字」。
 *   - 進階資訊（details）含「（數字）」或「（文字）」。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  FK, and, choose, cleanup, efBackend, filterOf, leaf, json, open, optionCount, putWith, resetEnv, rowsOf,
  screen, teardown, userEvent, waitFor, within,
} from '../event-filter-canvas-ui/fixtures'
import {
  editorBackend, openEditor, selectNode, waitForPut,
} from '../journey-canvas-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const keysBody = () => json({ profile_attributes: ['tier'], event_properties: ['amount', 'cart_total', 'price', 'quantity', 'coupon_code'], as_of: '2026-11-02T01:41:00Z' })
const ef = () => efBackend(undefined, { [`GET ${FK}`]: keysBody })

const OPS_ALL = ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NEQ', 'CONTAINS', 'IN', 'NOT_IN', 'IS_NULL', 'IS_NOT_NULL']
const OPS_NUM = ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NEQ', 'IS_NULL', 'IS_NOT_NULL']
const opValues = (row: HTMLElement) =>
  [...(within(row).getByLabelText('比較') as HTMLSelectElement).options].map((o) => o.value).filter(Boolean).sort()

async function newRow(g: HTMLElement): Promise<HTMLElement> {
  const first = within(g).queryByRole('button', { name: '加入第一個條件' })
  await userEvent.click(first ?? within(g).getByRole('button', { name: '加入條件' }))
  const row = await waitFor(() => {
    const r = rowsOf(g).at(-1)
    expect(r).toBeTruthy()
    return r!
  })
  await waitFor(() => expect(optionCount(within(row).getByLabelText('欄位'))).toBeGreaterThan(2))
  return row
}
async function manualRow(g: HTMLElement, key: string) {
  const row = await newRow(g)
  await choose(within(row).getByLabelText('欄位'), ['手動輸入欄位代碼'])
  await userEvent.type(await within(row).findByLabelText('欄位代碼'), key)
  return row
}
async function fieldRow(g: HTMLElement, key: string) {
  const row = await newRow(g)
  await choose(within(row).getByLabelText('欄位'), [`properties.${key}`])
  return row
}
const typeValue = async (row: HTMLElement, v: string) => {
  const box = within(row).getByLabelText('值')
  await userEvent.clear(box)
  await userEvent.type(box, v)
}
const asKind = (row: HTMLElement, kind: '文字' | '數字') =>
  within(within(row).getByRole('group', { name: /值當成/ })).queryByRole('radio', { name: kind }) ??
  within(within(row).getByRole('group', { name: /值當成/ })).getByRole('button', { name: kind })
const toggle = (row: HTMLElement) => within(row).queryByRole('group', { name: /值當成/ })

describe('事件篩選：型別不明欄位（手動輸入 amount）列出全部比較並分三組', () => {
  it('比較下拉有 11 個比較，分成「比大小（值會存成數字）」「比對內容」「有沒有填」三個 optgroup', async () => {
    ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    expect(opValues(row)).toEqual([...OPS_ALL].sort())
    const groups = [...row.querySelectorAll('optgroup')]
    expect(groups.map((o) => o.label)).toEqual(['比大小（值會存成數字）', '比對內容', '有沒有填'])
    expect([...groups[0].querySelectorAll('option')].map((o) => o.value)).toEqual(['GT', 'GTE', 'LT', 'LTE'])
  })

  it('amount 大於 500 → PUT event_filter = AND[properties.amount GT 500（JSON number）]；進階資訊寫（數字）', async () => {
    const ed = ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    await choose(within(row).getByLabelText('比較'), ['GT'])
    expect(within(row).getByLabelText('值').getAttribute('inputmode')).toBe('decimal')
    await typeValue(row, '500')
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.amount', 'GT', 500)))
    expect(typeof (filterOf(put, 'src_1')!.children[0] as { value: unknown }).value).toBe('number')
    expect(g.textContent).toContain('（數字）')
  })

  it('建議清單裡型別不明的鍵（coupon_code）同樣列全部比較', async () => {
    ef()
    const g = await open('加入購物車')
    const row = await fieldRow(g, 'coupon_code')
    expect(opValues(row)).toEqual([...OPS_ALL].sort())
  })

  it('比大小可填小數與負數：-1.5、0.25 存成 number', async () => {
    const ed = ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    await choose(within(row).getByLabelText('比較'), ['GTE'])
    await typeValue(row, '-1.5')
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.amount', 'GTE', -1.5)))
    await typeValue(row, '0.25')
    const put2 = await putWith(ed, 'src_1', (f) => JSON.stringify(f).includes('0.25'))
    expect(filterOf(put2, 'src_1')).toEqual(and(leaf('properties.amount', 'GTE', 0.25)))
  })
})

describe('事件篩選：「值當成：文字｜數字」', () => {
  it('EQ 在型別不明欄位預設文字（存 string，並提示會當成文字 "500" 比對）；切到數字存 number、提示消失', async () => {
    const ed = ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    await choose(within(row).getByLabelText('比較'), ['EQ'])
    await typeValue(row, '500')
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.amount', 'EQ', '500')))
    expect(row.textContent).toMatch(/會當成文字\s*[「"“]?500/)
    expect(row.textContent).toContain('（文字）')
    await userEvent.click(asKind(row, '數字'))
    const put2 = await putWith(ed, 'src_1', (f) => JSON.stringify(f).includes('"value":500'))
    expect(filterOf(put2, 'src_1')).toEqual(and(leaf('properties.amount', 'EQ', 500)))
    expect(row.textContent).not.toMatch(/會當成文字/)
    expect(row.textContent).toContain('（數字）')
  })

  it('提示不是錯誤：文字且像數字時不擋存檔、沒有 role=alert', async () => {
    const ed = ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    await choose(within(row).getByLabelText('比較'), ['EQ'])
    await typeValue(row, '1')
    await putWith(ed, 'src_1', (f) => f !== null)
    expect(within(row).queryByRole('alert')).toBeNull()
  })

  it('只在 EQ／NEQ／IN／NOT_IN 顯示；GT、CONTAINS、有填沒有切換', async () => {
    ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    for (const op of ['EQ', 'NEQ', 'IN', 'NOT_IN']) {
      await choose(within(row).getByLabelText('比較'), [op])
      expect(toggle(row), `${op} 要有切換`).toBeTruthy()
    }
    for (const op of ['GT', 'CONTAINS', 'IS_NULL']) {
      await choose(within(row).getByLabelText('比較'), [op])
      expect(toggle(row), `${op} 不該有切換`).toBeNull()
    }
  })

  it('數字型 IN：1, 2, 3 存成 [1,2,3]；有非數字項顯示「第 2 項『b』不是數字」且不寫入', async () => {
    const ed = ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    await choose(within(row).getByLabelText('比較'), ['IN'])
    await userEvent.click(asKind(row, '數字'))
    await typeValue(row, '1, b')
    const alert = await within(row).findByRole('alert')
    expect(alert.textContent).toMatch(/第\s*2\s*項.*b.*不是數字/)
    await new Promise((r) => setTimeout(r, 1200))
    expect(ed.puts().every((c) => (c.body as { definition: { nodes: Array<{ id: string; config: { event_filter?: unknown } }> } }).definition.nodes.find((n) => n.id === 'src_1')!.config.event_filter == null)).toBe(true)
    await typeValue(row, '1, 2, 3')
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.amount', 'IN', [1, 2, 3])))
  })
})

describe('事件篩選：已知數字欄（cart_total、price、quantity）', () => {
  it.each(['cart_total', 'price', 'quantity'])('%s 只列比大小＋等於／不等於＋有填／沒填；沒有「值當成」切換', async (key) => {
    ef()
    const g = await open('加入購物車')
    const row = await fieldRow(g, key)
    expect(opValues(row)).toEqual([...OPS_NUM].sort())
    expect(toggle(row)).toBeNull()
  })

  it('cart_total 等於 500 直接存 number（不需切換）', async () => {
    const ed = ef()
    const g = await open('加入購物車')
    const row = await fieldRow(g, 'cart_total')
    await choose(within(row).getByLabelText('比較'), ['EQ'])
    await typeValue(row, '500')
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.cart_total', 'EQ', 500)))
  })
})

describe('事件篩選：數字驗證錯誤（行內 role=alert、aria-invalid、不寫入草稿）', () => {
  it.each(['1,000', '500元', '1e3', '.5', '5 0', '１２'])('「大於」值 %s 不是數字：顯示「要填數字」與 1000 的寫法提示', async (bad) => {
    const ed = ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    await choose(within(row).getByLabelText('比較'), ['GT'])
    await typeValue(row, bad)
    const alert = await within(row).findByRole('alert')
    expect(alert.textContent).toContain('要填數字')
    expect(alert.textContent).toContain('1000')
    expect(within(row).getByLabelText('值').getAttribute('aria-invalid')).toBe('true')
    await new Promise((r) => setTimeout(r, 1200))
    const wrote = ed.puts().some((c) => JSON.stringify(c.body).includes('"operator":"GT"'))
    expect(wrote, '壞值不可寫入').toBe(false)
  })

  it('改成合法數字後錯誤消失並寫入', async () => {
    const ed = ef()
    const g = await open('加入購物車')
    const row = await manualRow(g, 'amount')
    await choose(within(row).getByLabelText('比較'), ['GT'])
    await typeValue(row, '1,000')
    await within(row).findByRole('alert')
    await typeValue(row, '1000')
    await waitFor(() => expect(within(row).queryByRole('alert')).toBeNull())
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.amount', 'GT', 1000)))
    expect(within(row).getByLabelText('值').getAttribute('aria-invalid')).not.toBe('true')
  })

  it('等事件節點同一套：amount 大於 500 存 number', async () => {
    const ed = ef()
    const g = await open('等結帳 24h', /^只算符合條件的事件/)
    const row = await manualRow(g, 'amount')
    await choose(within(row).getByLabelText('比較'), ['GT'])
    await typeValue(row, '500')
    const put = await putWith(ed, 'wait_ev', (f) => f !== null)
    expect(filterOf(put, 'wait_ev')).toEqual(and(leaf('properties.amount', 'GT', 500)))
  })
})

describe('條件節點同一套（欄位根 trigger.properties.）', () => {
  const condRow = async () => {
    editorBackend()
    await openEditor()
    await selectNode('是 VIP？')
    const sel = (await screen.findByLabelText('欄位')) as HTMLSelectElement
    await waitFor(() => expect([...sel.options].some((o) => o.value === 'trigger.properties.cart_id')).toBe(true))
    return { sel, row: sel.closest('li, .cond-row') as HTMLElement }
  }

  it('型別不明的事件屬性（cart_id）：全列比較並分三組', async () => {
    const { sel, row } = await condRow()
    await userEvent.selectOptions(sel, 'trigger.properties.cart_id')
    expect(opValues(row)).toEqual([...OPS_ALL].sort())
    expect([...row.querySelectorAll('optgroup')].map((o) => o.label)).toEqual(['比大小（值會存成數字）', '比對內容', '有沒有填'])
  })

  it('已知數字欄 trigger.properties.cart_total 不列 CONTAINS／IN／NOT_IN', async () => {
    const { sel, row } = await condRow()
    await userEvent.selectOptions(sel, 'trigger.properties.cart_total')
    expect(opValues(row)).toEqual([...OPS_NUM].sort())
  })

  it('手動輸入 trigger.properties.amount 大於 500 → 條件存 GT、value 500（number）', async () => {
    const ed = editorBackend()
    await openEditor()
    await selectNode('是 VIP？')
    const sel = await screen.findByLabelText('欄位')
    const row = sel.closest('li, .cond-row') as HTMLElement
    await userEvent.selectOptions(sel, '__custom__')
    const code = await within(row).findByLabelText('欄位代碼')
    await userEvent.clear(code)
    await userEvent.type(code, 'trigger.properties.amount')
    await choose(within(row).getByLabelText('比較'), ['GT'])
    await typeValue(row, '500')
    const put = await waitForPut((b) => JSON.stringify(b.definition).includes('"field":"trigger.properties.amount"') && JSON.stringify(b.definition).includes('"value":500'), ed)
    const cond = (put.definition.nodes.find((n) => n.id === 'cond_vip')!.config as { condition: { children: unknown[] } }).condition
    expect(cond.children).toEqual([{ type: 'attribute', field: 'trigger.properties.amount', operator: 'GT', value: 500 }])
  })

  it('條件節點「大於」填 1,000：行內「要填數字」，不寫入', async () => {
    const ed = editorBackend()
    await openEditor()
    await selectNode('是 VIP？')
    const sel = await screen.findByLabelText('欄位')
    const row = sel.closest('li, .cond-row') as HTMLElement
    await waitFor(() => expect([...(sel as HTMLSelectElement).options].some((o) => o.value === 'trigger.properties.cart_total')).toBe(true))
    await userEvent.selectOptions(sel, 'trigger.properties.cart_total')
    await choose(within(row).getByLabelText('比較'), ['GT'])
    await typeValue(row, '1,000')
    const alert = await within(row).findByRole('alert')
    expect(alert.textContent).toContain('要填數字')
    expect(within(row).getByLabelText('值').getAttribute('aria-invalid')).toBe('true')
    await new Promise((r) => setTimeout(r, 1200))
    expect(ed.puts().some((c) => JSON.stringify(c.body).includes('"operator":"GT"'))).toBe(false)
  })
})

