// @vitest-environment jsdom
/**
 * condition-compare-ux：比較選單、型別不明說明、句子預覽（docs/ui/canvas-interaction.md §3）。
 * 條件節點與事件篩選同一套（F-32、F-39）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { FK, and, choose, cleanup, efBackend, leaf, open, optionCount, putWith, resetEnv, rowsOf, teardown, userEvent, waitFor, within } from '../event-filter-canvas-ui/fixtures'
import { andOf, cartLeaf, groupsOf, openCond, rowLabel, tierLeaf } from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const ALL_TEXTS = ['大於（>）', '至少（≥）', '小於（<）', '至多（≤）', '等於', '不等於', '包含', '是其中之一', '不是其中任何一個', '有填', '沒填']
const GROUPS_ALL = ['數字比大小', '比對內容', '有沒有填']
const row = (panel: HTMLElement, i = 0) => within(panel).getAllByLabelText('欄位')[i].closest('li, .cond-row') as HTMLElement
const cmpOf = (r: HTMLElement) => within(r).getByLabelText('比較') as HTMLSelectElement
const flat = (el: HTMLElement) => (el.textContent ?? '').replace(/\s+/g, '')

describe('條件節點：比較選單', () => {
  it('型別不明欄位（tier）：11 個選項只寫比較方式（全形括號符號），不帶欄位名；三組名稱統一', async () => {
    const { panel } = await openCond(andOf(tierLeaf('EQ', 'VIP')))
    const sel = cmpOf(row(panel))
    expect(rowLabel(sel)).toEqual(ALL_TEXTS)
    expect(groupsOf(sel).map((g) => g.label)).toEqual(GROUPS_ALL)
    expect(groupsOf(sel).map((g) => g.values)).toEqual([
      ['GT', 'GTE', 'LT', 'LTE'],
      ['EQ', 'NEQ', 'CONTAINS', 'IN', 'NOT_IN'],
      ['IS_NOT_NULL', 'IS_NULL'],
    ])
    for (const t of rowLabel(sel)) expect(t).not.toMatch(/tier|會員等級|500/)
  })

  it('已知數字欄（購物車金額）：只列「數字比大小」「比對內容（等於、不等於）」「有沒有填」，不列包含／是其中之一', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500)))
    const sel = cmpOf(row(panel))
    expect(groupsOf(sel)).toEqual([
      { label: '數字比大小', values: ['GT', 'GTE', 'LT', 'LTE'] },
      { label: '比對內容', values: ['EQ', 'NEQ'] },
      { label: '有沒有填', values: ['IS_NOT_NULL', 'IS_NULL'] },
    ])
    expect(rowLabel(sel)).toEqual(['大於（>）', '至少（≥）', '小於（<）', '至多（≤）', '等於', '不等於', '有填', '沒填'])
  })

  it('選項文字不隨欄位改變：換欄位後仍是同樣的選項文字', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500)))
    const r = row(panel)
    const pick = (t: string) => ['大於（>）', '等於'].includes(t)
    const before = rowLabel(cmpOf(r)).filter(pick)
    await waitFor(() => expect([...(within(r).getByLabelText('欄位') as HTMLSelectElement).options].some((o) => o.value === 'tier')).toBe(true))
    await userEvent.selectOptions(within(r).getByLabelText('欄位'), 'tier')
    expect(rowLabel(cmpOf(r)).filter(pick)).toEqual(before)
  })

  it('型別不明說明改成規格文字（含欄位顯示名）；已知型別沒有這段說明', async () => {
    const { panel } = await openCond(andOf({ type: 'attribute', field: 'city', operator: 'EQ', value: 'Taipei' }, cartLeaf('GT', 500)))
    const text = flat(panel)
    expect(text).toContain('系統不知道『居住城市』是數字還是文字。要比大小，請在比較欄選『數字比大小』那一組的項目（大於、至少、小於、至多），500會當成數字比對。')
    expect(text).not.toContain('請選「比大小」')
    expect(text.match(/系統不知道/g)?.length).toBe(1)
  })
})

describe('條件節點：句子預覽（面板頂端）', () => {
  it('「客戶要全部符合：『購物車金額』大於 500；『會員等級』等於 gold。」，位置在欄位列之前', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500), tierLeaf('EQ', 'gold')))
    const want = '客戶要全部符合：『購物車金額』大於500；『會員等級』等於gold。'
    const els = [...panel.querySelectorAll<HTMLElement>('p, div, span, output')].filter((n) => flat(n) === want)
    expect(els.length, '找不到句子預覽').toBeGreaterThan(0)
    const firstField = within(panel).getAllByLabelText('欄位')[0]
    expect(els[0].compareDocumentPosition(firstField) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('改比較與值，句子即時更新；有填不帶值', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500)))
    const r = row(panel)
    expect(flat(panel)).toContain('『購物車金額』大於500。')
    await userEvent.selectOptions(cmpOf(r), 'GTE')
    await waitFor(() => expect(flat(panel)).toContain('『購物車金額』至少500。'))
    const box = within(r).getByLabelText('值')
    await userEvent.clear(box)
    await userEvent.type(box, '800')
    await waitFor(() => expect(flat(panel)).toContain('『購物車金額』至少800。'))
    await userEvent.selectOptions(cmpOf(r), 'IS_NOT_NULL')
    await waitFor(() => expect(flat(panel)).toContain('『購物車金額』有填。'))
  })

  it('進階資訊仍顯示欄位代碼', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500)))
    expect(row(panel).querySelector('details')!.textContent).toContain('trigger.properties.cart_total')
  })
})

describe('事件篩選：同一套比較選單', () => {
  const FKEYS = () => ({
    [`GET ${FK}`]: () =>
      new Response(JSON.stringify({ profile_attributes: [], event_properties: ['amount', 'cart_total', 'utm_source'], as_of: '2026-11-02T01:41:00Z' }), {
        status: 200, headers: { 'content-type': 'application/json' },
      }),
  })
  const newRow = async (g: HTMLElement) => {
    await userEvent.click(within(g).getByRole('button', { name: '加入第一個條件' }))
    const r = await waitFor(() => { const x = rowsOf(g).at(-1); expect(x).toBeTruthy(); return x! })
    await waitFor(() => expect(optionCount(within(r).getByLabelText('欄位'))).toBeGreaterThan(2))
    return r
  }

  it('型別不明（amount）：選項文字與分組名同條件節點', async () => {
    efBackend(undefined, FKEYS())
    const g = await open('加入購物車')
    const r = await newRow(g)
    await choose(within(r).getByLabelText('欄位'), ['properties.amount'])
    const sel = cmpOf(r)
    expect(rowLabel(sel)).toEqual(ALL_TEXTS)
    expect(groupsOf(sel).map((x) => x.label)).toEqual(GROUPS_ALL)
  })

  it('已知數字欄（cart_total）只列等於／不等於於比對內容；已知文字欄（utm_source）不列「數字比大小」組', async () => {
    efBackend(undefined, FKEYS())
    const g = await open('加入購物車')
    const r = await newRow(g)
    await choose(within(r).getByLabelText('欄位'), ['properties.cart_total'])
    expect(groupsOf(cmpOf(r)).map((x) => x.label)).toEqual(GROUPS_ALL)
    expect(groupsOf(cmpOf(r))[1].values).toEqual(['EQ', 'NEQ'])
    await choose(within(r).getByLabelText('欄位'), ['properties.utm_source'])
    expect(groupsOf(cmpOf(r))).toEqual([
      { label: '比對內容', values: ['EQ', 'NEQ', 'CONTAINS', 'IN', 'NOT_IN'] },
      { label: '有沒有填', values: ['IS_NOT_NULL', 'IS_NULL'] },
    ])
  })

  it('選了「大於（>）」存的仍是 GT／number（契約不變）', async () => {
    const ed = efBackend(undefined, FKEYS())
    const g = await open('加入購物車')
    const r = await newRow(g)
    await choose(within(r).getByLabelText('欄位'), ['properties.cart_total'])
    await choose(within(r).getByLabelText('比較'), ['大於（>）'])
    await userEvent.type(within(r).getByLabelText('值'), '500')
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect((put.definition.nodes.find((n) => n.id === 'src_1')!.config as { event_filter: unknown }).event_filter).toEqual(and(leaf('properties.cart_total', 'GT', 500)))
  })
})
