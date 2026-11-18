import { describe, expect, it } from 'vitest'
import {
  blockSummary,
  cardFilterSummary,
  countIncomplete,
  countLeaves,
  depthOf,
  emptyGroup,
  emptyLeaf,
  filterIssues,
  filterLeafCount,
  filterSentence,
  isNumericField,
  issuesAt,
  leafInlineErrors,
  leafWire,
  mapTree,
  opsFor,
  parseValue,
  toWire,
  treeFromWire,
  wireKey,
  type FLeaf,
} from '../../../src/canvas/eventFilter'
import { summaryOf } from '../../../src/canvas/model'

const leaf = (over: Partial<FLeaf>): FLeaf => ({ ...emptyLeaf(), ...over })
const GT500 = { op: 'AND', children: [{ type: 'attribute', field: 'properties.cart_total', operator: 'GT', value: 500 }] }

describe('parseValue：依比較語意決定 JSON 型別', () => {
  it('大於等比較送 number；非數字回錯誤', () => {
    expect(parseValue({ operator: 'GT', text: '500', num: true })).toEqual({ ok: true, value: 500 })
    expect(parseValue({ operator: 'LTE', text: 'abc', num: false })).toMatchObject({ ok: false, error: expect.stringContaining('數字') })
  })
  it('沒填完：沒有比較、值是空白', () => {
    expect(parseValue({ operator: '', text: 'x', num: false })).toEqual({ ok: false })
    expect(parseValue({ operator: 'EQ', text: '  ', num: false })).toEqual({ ok: false })
    expect(parseValue({ operator: 'IN', text: ' , ', num: false })).toEqual({ ok: false })
  })
  it('沒有值的比較不帶 value', () => {
    expect(parseValue({ operator: 'IS_NOT_NULL', text: 'zzz', num: false })).toEqual({ ok: true })
  })
  it('IN：逗號分隔、去空白；數字欄送 number 陣列，混入文字回錯誤', () => {
    expect(parseValue({ operator: 'IN', text: 'line, email', num: false })).toEqual({ ok: true, value: ['line', 'email'] })
    expect(parseValue({ operator: 'NOT_IN', text: '1, 2', num: true })).toEqual({ ok: true, value: [1, 2] })
    expect(parseValue({ operator: 'IN', text: '1, x', num: true })).toMatchObject({ ok: false, error: expect.any(String) })
  })
  it('EQ：數字欄送 number，文字欄送 string；CONTAINS 送 string', () => {
    expect(parseValue({ operator: 'EQ', text: '5', num: true })).toEqual({ ok: true, value: 5 })
    expect(parseValue({ operator: 'NEQ', text: 'x', num: true })).toMatchObject({ ok: false, error: expect.any(String) })
    expect(parseValue({ operator: 'EQ', text: '5', num: false })).toEqual({ ok: true, value: '5' })
    expect(parseValue({ operator: 'CONTAINS', text: 'ab', num: false })).toEqual({ ok: true, value: 'ab' })
  })
})

describe('欄位與比較', () => {
  it('數字欄判斷：內建與後綴', () => {
    expect(isNumericField('properties.cart_total')).toBe(true)
    expect(isNumericField('properties.line_amount')).toBe(true)
    expect(isNumericField('properties.coupon_code')).toBe(false)
  })
  it('opsFor：數字欄沒有 CONTAINS／IN；沒選欄位列全部；已選的舊比較保留', () => {
    const num = opsFor('properties.cart_total', '').map((o) => o.value)
    expect(num).toContain('GT')
    expect(num).not.toContain('IN')
    expect(opsFor('', '').length).toBe(11)
    expect(opsFor('properties.cart_total', 'CONTAINS').map((o) => o.value)).toContain('CONTAINS')
    expect(opsFor('properties.utm_source', '').map((o) => o.value)).not.toContain('GT')
    expect(opsFor('properties.amount', '').length).toBe(11)
  })
})

describe('leafWire／行內錯誤', () => {
  it('完整條件才有 wire；手動鍵補 properties. 前綴', () => {
    expect(leafWire(leaf({ field: 'properties.cart_total', operator: 'GT', text: '500', num: true }))).toEqual({ type: 'attribute', field: 'properties.cart_total', operator: 'GT', value: 500 })
    expect(leafWire(leaf({ customKey: 'my_key', operator: 'IS_NULL' }))).toEqual({ type: 'attribute', field: 'properties.my_key', operator: 'IS_NULL' })
    expect(leafWire(leaf({ field: 'tier', operator: 'EQ', text: 'VIP' }))).toBeNull()
    expect(leafWire(leaf({ field: 'properties.cart_total' }))).toBeNull()
    expect(leafWire(leaf({ field: 'properties.cart_total', operator: 'GT', text: 'x' }))).toBeNull()
  })
  it('行內錯誤：欄位代碼格式、數字比較填非數字；沒填完不算錯；原樣載入的不檢查', () => {
    expect(leafInlineErrors(leaf({ customKey: 'Cart Total' })).field).toContain('格式不符')
    expect(leafInlineErrors(leaf({ field: 'properties.cart_total', operator: 'GT', text: 'abc' })).value).toContain('數字')
    expect(leafInlineErrors(leaf({ field: 'properties.cart_total' }))).toEqual({})
    expect(leafInlineErrors(leaf({ customKey: 'Bad Key', raw: { type: 'attribute', field: 'x', operator: 'EQ' } }))).toEqual({})
  })
})

describe('樹與契約互轉', () => {
  it('載入後原樣寫回（含 raw 與無法編輯的葉節點），路徑依寫入後的位置', () => {
    const seg = { type: 'segment', segment_id: 's1' }
    const filter = { op: 'AND', children: [{ type: 'attribute', field: 'tier', operator: 'EQ', value: 'VIP' }, seg] }
    const tree = treeFromWire(filter)
    const { filter: out, paths } = toWire(tree)
    expect(out).toEqual(filter)
    expect([...paths.values()]).toEqual(['event_filter', 'event_filter.children[0]', 'event_filter.children[1]'])
    expect(tree.children[1].kind).toBe('unsup')
  })
  it('沒填完的條件與空群組不寫入；全空為 null', () => {
    const tree = { ...emptyGroup(), children: [emptyLeaf(), emptyGroup()] }
    expect(toWire(tree).filter).toBeNull()
    const full = leaf({ field: 'properties.cart_total', operator: 'GT', text: '1', num: true })
    const t2 = { ...emptyGroup(), op: 'OR' as const, children: [emptyLeaf(), full, { ...emptyGroup(), children: [{ ...full, id: 'dup' }] }] }
    const w = toWire(t2)
    expect(w.filter?.op).toBe('OR')
    expect(w.filter?.children.length).toBe(2)
    expect(w.paths.get(full.id)).toBe('event_filter.children[0]')
  })
  it('根不是群組時包成 AND；null 為空樹', () => {
    expect(treeFromWire({ type: 'attribute', field: 'properties.a', operator: 'EQ', value: 'x' }).children.length).toBe(1)
    expect(treeFromWire(null).children).toEqual([])
    expect(treeFromWire(undefined).op).toBe('AND')
  })
  it('統計與更新：葉節點數、未完成數、深度、mapTree 移除／取代', () => {
    const a = leaf({ field: 'properties.a', operator: 'EQ', text: 'x' })
    const b = emptyLeaf()
    const inner = { ...emptyGroup(), children: [b] }
    const tree = { ...emptyGroup(), children: [a, inner] }
    expect(countLeaves(tree)).toBe(2)
    expect(countIncomplete(tree)).toBe(1)
    expect(depthOf(tree)).toBe(2)
    expect(mapTree(tree, a.id, () => null).children.length).toBe(1)
    expect(countLeaves(mapTree(tree, b.id, () => null))).toBe(1)
    expect((mapTree(tree, b.id, (n) => ({ ...(n as FLeaf), text: 'z' })).children[1] as { children: FLeaf[] }).children[0].text).toBe('z')
  })
  it('wireKey：null 與 undefined 相同', () => {
    expect(wireKey(undefined)).toBe(wireKey(null))
  })
})

describe('白話摘要', () => {
  it('單一條件句與多條件連接詞、群組', () => {
    expect(filterSentence(GT500)).toBe('「購物車金額」大於 500')
    const f = {
      op: 'OR',
      children: [
        { type: 'attribute', field: 'properties.utm_source', operator: 'IN', value: ['line', 'email'] },
        { op: 'NOT', children: [{ type: 'attribute', field: 'properties.coupon_code', operator: 'IS_NULL' }] },
        { op: 'AND', children: [GT500.children[0], GT500.children[0]] },
      ],
    }
    const s = filterSentence(f)
    expect(s).toContain('line、email')
    expect(s).toContain(' 或 ')
    expect(s).toContain('不是（「優惠碼」沒填）')
    expect(s).toContain('（「購物車金額」大於 500 且 「購物車金額」大於 500）')
    expect(filterSentence({ type: 'segment' })).toContain('無法編輯')
  })
  it('節點卡：沒有→null；1 項只限；2 項以上符合 N 項；等事件 N 項條件', () => {
    expect(cardFilterSummary('source', null)).toBeNull()
    expect(cardFilterSummary('source', { op: 'AND', children: [] })).toBeNull()
    expect(cardFilterSummary('source', GT500)).toBe('只限「購物車金額」大於 500')
    expect(cardFilterSummary('source', { op: 'AND', children: [GT500.children[0], GT500.children[0]] })).toBe('符合 2 項條件')
    expect(cardFilterSummary('wait', GT500, '24 小時')).toBe('24 小時・1 項條件')
    expect(filterLeafCount(GT500.children[0])).toBe(1)
  })
  it('model.summaryOf 帶入篩選；沒有篩選維持原樣', () => {
    const src = { id: 's', type: 'EventSourceNode', name: null, config: { event_name: 'add_to_cart', event_filter: GT500 }, outputs: {} }
    expect(summaryOf(src)).toContain('只限')
    expect(summaryOf({ ...src, config: { event_name: 'add_to_cart' } })).toBe('事件 add_to_cart')
    const wait = { id: 'w', type: 'WaitForEventNode', name: null, config: { event_name: 'x', timeout: 'PT24H', event_filter: GT500 }, outputs: {} }
    expect(summaryOf(wait)).toBe('24 小時・1 項條件')
    expect(summaryOf({ ...wait, config: { event_name: 'x', timeout: null, event_filter: GT500 } })).toBe('不設時限・1 項條件')
    expect(summaryOf({ ...wait, config: { event_name: 'x', timeout: null } })).toBe('事件 x，不設時限')
    expect(summaryOf({ ...wait, config: { event_name: 'x', timeout: 'PT2H' } })).toBe('事件 x，最多 2 小時')
  })
  it('blockSummary：入口、等事件（逾時／不設時限）、沒填完提示', () => {
    const base = { event: 'add_to_cart', unlimited: false, incomplete: 0 }
    expect(blockSummary({ ...base, kind: 'source', filter: GT500 as never }).text).toContain('才會讓客戶進入')
    expect(blockSummary({ ...base, kind: 'wait', filter: GT500 as never }).text).toContain('繼續等到逾時')
    expect(blockSummary({ ...base, kind: 'wait', unlimited: true, filter: GT500 as never }).text).toContain('繼續等下去')
    expect(blockSummary({ ...base, kind: 'source', filter: null }).tone).toBe('todo')
    expect(blockSummary({ ...base, kind: 'wait', filter: null }).text).toContain('都算等到')
    expect(blockSummary({ ...base, kind: 'source', filter: null, incomplete: 1 }).text).toContain('尚未完成')
    expect(blockSummary({ ...base, event: '', kind: 'source', filter: GT500 as never, incomplete: 2 }).text).toMatch(/尚未填事件名稱.*2 項條件尚未完成/)
  })
})

describe('驗證錯誤對應', () => {
  const issues = [
    { field: 'definition.nodes[1].config.event_filter.children[0].value', message: 'v' },
    { field: 'definition.nodes[1].config.event_filter.children[0].field', message: 'f' },
    { field: 'definition.nodes[1].config.event_filter.children[0].operator', message: 'o' },
    { field: 'definition.nodes[1].config.event_filter.children[0]', message: 'r' },
    { field: 'definition.nodes[1].config.event_filter', message: 'root' },
    { field: 'definition.nodes[2].config.event_filter', message: 'other' },
    { field: 'definition.nodes[1].config.timeout', message: 'x' },
  ]
  it('只取該節點的 event_filter 錯誤，依路徑分到欄位／比較／值／整列', () => {
    const list = filterIssues(issues, 1)
    expect(list.length).toBe(5)
    expect(issuesAt(list, 'event_filter.children[0]')).toEqual({ row: ['r'], field: ['f'], operator: ['o'], value: ['v'] })
    expect(issuesAt(list, 'event_filter').row).toEqual(['root'])
    expect(issuesAt(list, undefined).row).toEqual([])
  })
})
