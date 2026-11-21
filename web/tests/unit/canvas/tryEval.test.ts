import { describe, expect, it } from 'vitest'
import { FILTER_OPS, opMenuLabel, opsFor, OP_GROUPS, unknownKindHint } from '../../../src/canvas/eventFilter'
import { OPERATORS, type Cond, type CondLeaf } from '../../../src/canvas/model'
import { buildExamples, conditionSentence, evalLeaf, evalTree, exampleFor, fragment, hasBadNot, hasUnsupported, isAttrLeaf, leavesOf, parseSample, prune, sampleFields } from '../../../src/canvas/tryEval'

const MISSING = undefined
// 契約 docs/contracts/journeys.md §4.1 共用測試向量（逐條）：[#, actual, operator, value, 結果]
const VECTORS: Array<[number, unknown, string, unknown, boolean]> = [
  [1, 600, 'GT', 500, true],
  [2, 500, 'GT', 500, false],
  [3, 500, 'GTE', 500, true],
  [4, 400, 'LT', 500, true],
  [5, 500.0, 'EQ', 500, true],
  [6, '600', 'GT', 500, false],
  [7, 600, 'GT', '500', false],
  [8, '500', 'EQ', 500, false],
  [9, '500', 'NEQ', 500, true],
  [10, 'Gold', 'EQ', 'gold', false],
  [11, 'gold', 'IN', ['gold', 'vip'], true],
  [12, 500, 'IN', ['500'], false],
  [13, 'silver', 'NOT_IN', ['gold', 'vip'], true],
  [14, 'hello world', 'CONTAINS', 'world', true],
  [15, ['a', 'b'], 'CONTAINS', 'a', true],
  [16, 600, 'CONTAINS', '6', false],
  [17, true, 'EQ', 'true', false],
  [18, MISSING, 'IS_NULL', undefined, true],
  [19, null, 'IS_NULL', undefined, true],
  [20, '', 'IS_NULL', undefined, false],
  [21, MISSING, 'IS_NOT_NULL', undefined, false],
  [22, MISSING, 'NEQ', 'gold', false],
  [23, MISSING, 'NOT_IN', ['gold'], false],
  [24, MISSING, 'GT', 500, false],
]
const leaf = (operator: string, value?: unknown, field = 'tier'): CondLeaf => (value === undefined ? { type: 'attribute', field, operator } : { type: 'attribute', field, operator, value })

describe('契約 §4.1 測試向量（25 條）', () => {
  it.each(VECTORS)('#%i', (_n, actual, op, value, want) => {
    expect(evalLeaf(actual, op, value)).toBe(want)
    // 經整棵樹（單一葉節點）判斷也一致
    expect(evalTree(leaf(op, value), () => actual)).toBe(want)
  })
  it('#25 NOT 群組包 EQ：缺值時內層不成立，NOT 翻成成立', () => {
    const c: Cond = { op: 'NOT', children: [leaf('EQ', 'gold')] }
    expect(evalTree(c, () => MISSING)).toBe(true)
    expect(evalTree({ op: 'NOT', children: [] }, () => MISSING)).toBe(false)
  })
  it('向量表共 25 條（含 #25）', () => {
    expect(VECTORS.length + 1).toBe(25)
  })
  it('其他：未知運算子不成立；OR／AND／陣列相等', () => {
    expect(evalLeaf(1, 'WHAT', 1)).toBe(false)
    expect(evalLeaf(['a'], 'EQ', ['a'])).toBe(true)
    expect(evalLeaf('a', 'IN', 'a')).toBe(false)
    expect(evalLeaf('a', 'NOT_IN', 'a')).toBe(false)
    expect(evalLeaf(['a'], 'CONTAINS', 'b')).toBe(false)
    const or: Cond = { op: 'OR', children: [leaf('EQ', 'a'), leaf('EQ', 'b')] }
    expect(evalTree(or, () => 'b')).toBe(true)
    expect(evalTree({ op: 'AND', children: [leaf('EQ', 'a'), leaf('EQ', 'b')] }, () => 'b')).toBe(false)
  })
})

describe('樣本轉型', () => {
  it('空白 = 缺值；數字樣子才轉 number；字串原樣', () => {
    expect(parseSample('', true)).toBeUndefined()
    expect(parseSample('   ', false)).toBeUndefined()
    expect(parseSample(' 600 ', true)).toBe(600)
    expect(parseSample('500.0', true)).toBe(500)
    expect(parseSample('abc', true)).toBe('abc')
    expect(parseSample('600', false)).toBe('600')
  })
  it('sampleFields：每欄位一個、值是數字（或全數字陣列）就當數字', () => {
    const f = sampleFields([leaf('GT', 500, 'a'), leaf('EQ', 'x', 'a'), leaf('IN', [1, 2], 'b'), leaf('EQ', 'gold', 'tier')])
    expect(f.map((x) => [x.field, x.asNumber])).toEqual([['a', true], ['b', true], ['tier', false]])
  })
})

describe('整理條件與句子', () => {
  it('isAttrLeaf／hasUnsupported', () => {
    expect(isAttrLeaf(leaf('EQ', 'x'))).toBe(true)
    expect(isAttrLeaf({ type: 'behavior' })).toBe(false)
    expect(isAttrLeaf(null)).toBe(false)
    expect(hasUnsupported({ op: 'AND', children: [leaf('EQ', 'x'), { type: 'segment' }] })).toBe(true)
    expect(hasUnsupported({ op: 'AND', children: [leaf('EQ', 'x')] })).toBe(false)
    expect(hasUnsupported(null)).toBe(true)
    expect(hasUnsupported({ op: 'AND' })).toBe(false)
  })
  it('prune 去掉未選欄位與空群組；leavesOf 依序攤平', () => {
    const c: Cond = { op: 'AND', children: [leaf('EQ', 'x', ''), { op: 'OR', children: [leaf('EQ', 'y', '')] }, leaf('EQ', 'gold')] }
    const p = prune(c)!
    expect(leavesOf(p).map((l) => l.field)).toEqual(['tier'])
    expect(prune({ op: 'AND', children: [] })).toBeNull()
    expect(prune({ type: 'segment' } as unknown as Cond)).toBeNull()
  })
  it('conditionSentence：AND／OR／NOT／巢狀／有填／值沒填／空', () => {
    expect(conditionSentence({ op: 'AND', children: [leaf('GT', 500, 'trigger.properties.cart_total'), leaf('EQ', 'gold')] })).toBe('客戶要全部符合：『購物車金額』大於 500；『會員等級』等於 gold。')
    expect(conditionSentence({ op: 'OR', children: [leaf('IS_NOT_NULL'), leaf('IN', ['a', 'b'])] })).toBe('客戶要任一符合：『會員等級』有填；『會員等級』是其中之一 a、b。')
    expect(conditionSentence({ op: 'NOT', children: [leaf('EQ', '')] })).toBe('客戶要都不符合：『會員等級』等於 …。')
    expect(conditionSentence({ op: 'AND', children: [leaf('EQ', 'a'), { op: 'OR', children: [leaf('EQ', 'b'), leaf('EQ', 'c')] }] })).toBe(
      '客戶要全部符合：『會員等級』等於 a；（任一符合：『會員等級』等於 b；『會員等級』等於 c）。',
    )
    expect(conditionSentence({ op: 'AND', children: [] })).toBe('還沒有可判斷的條件。')
    expect(fragment(leaf('IS_NULL'))).toBe('沒填')
  })
})

describe('範例客戶', () => {
  const ok = (l: CondLeaf) => evalLeaf(exampleFor(l, true).value, l.operator, l.value)
  const bad = (l: CondLeaf) => evalLeaf(exampleFor(l, false).value, l.operator, l.value)
  it.each([
    leaf('GT', 500), leaf('GTE', 500), leaf('LT', 500), leaf('LTE', 500),
    leaf('EQ', 'gold'), leaf('NEQ', 'gold'), leaf('EQ', 500), leaf('NEQ', 500), leaf('CONTAINS', 'wor'),
    leaf('IN', ['gold', 'vip']), leaf('NOT_IN', ['gold', 'vip']), leaf('IN', [1, 2]), leaf('IS_NULL'), leaf('IS_NOT_NULL'),
    leaf('EQ', 'silver'),
  ])('%j：會符合那位成立、不會符合那位不成立', (l) => {
    expect(ok(l)).toBe(true)
    expect(bad(l)).toBe(false)
  })
  it('邊界說明與缺數字', () => {
    expect(exampleFor(leaf('GT', 500), false).note).toBe('剛好等於不算')
    expect(exampleFor(leaf('GTE', 500), true).note).toBe('剛好等於也算')
    expect(exampleFor(leaf('LTE', 500), true).note).toBe('剛好等於也算')
    expect(exampleFor(leaf('GT', ''), true).note).toBe('請先填數字')
    expect(exampleFor(leaf('WHAT', 'x'), true).shown).toBe('沒填')
  })
  it('NOT 要恰好一個子條件、空群組不成立（同引擎 ConditionEvaluator）', () => {
    const silver = () => 'silver'
    expect(evalTree({ op: 'NOT', children: [leaf('EQ', 'gold'), leaf('EQ', 'vip')] }, silver)).toBe(false)
    expect(evalTree({ op: 'NOT', children: [leaf('EQ', 'gold')] }, silver)).toBe(true)
    expect(evalTree({ op: 'AND', children: [] }, silver)).toBe(false)
    expect(evalTree({ op: 'OR', children: [] }, silver)).toBe(false)
    expect(hasBadNot({ op: 'AND', children: [{ op: 'NOT', children: [leaf('EQ', 'a'), leaf('EQ', 'b')] }] })).toBe(true)
    expect(hasBadNot({ op: 'NOT', children: [leaf('EQ', 'a')] })).toBe(false)
  })
  const rowOf = (rows: NonNullable<ReturnType<typeof buildExamples>>['yes'], field: string) => rows!.filter((r) => r.field === field)
  it('buildExamples：每個欄位一個值；AND 的不會符合那位只有一個欄位不同、說明在該欄位下', () => {
    const and: Cond = { op: 'AND', children: [leaf('GT', 500, 'a'), leaf('EQ', 'gold'), leaf('IS_NOT_NULL', undefined, 'c')] }
    const e = buildExamples(and)!
    expect(e.yes!.every((r) => r.bads.length === 0)).toBe(true)
    expect(e.no!.filter((r) => r.bads.length).length).toBe(1)
    const one = buildExamples({ op: 'AND', children: [leaf('GT', 500)] })!
    expect(one.no![0].bads).toEqual(['不合：要大於 500（剛好等於不算）'])
    expect(buildExamples({ op: 'AND', children: [leaf('GTE', 500)] })!.yes![0].notes).toEqual(['剛好等於也算'])
  })
  it('同欄位範圍條件（GT 500 且 LT 1000）：兩位客戶各只有一個金額，判定正確', () => {
    const root: Cond = { op: 'AND', children: [leaf('GT', 500, 'amt'), leaf('LT', 1000, 'amt')] }
    const e = buildExamples(root)!
    expect(e.yes!.length).toBe(1)
    expect(e.no!.length).toBe(1)
    const get = (rows: typeof e.yes) => rowOf(rows, 'amt')[0].value
    expect(evalTree(root, () => get(e.yes))).toBe(true)
    expect(evalTree(root, () => get(e.no))).toBe(false)
    expect(e.no![0].bads.length).toBeGreaterThan(0)
  })
  it('同欄位互斥（EQ gold 且 EQ vip）：不產生會符合的客戶，不會符合那位仍有', () => {
    const e = buildExamples({ op: 'AND', children: [leaf('EQ', 'gold'), leaf('EQ', 'vip')] })!
    expect(e.yes).toBeNull()
    expect(e.no).not.toBeNull()
    expect(e.no!.length).toBe(1)
  })
  it('OR、NOT、多子條件 NOT、永遠成立、太多組合', () => {
    const or = buildExamples({ op: 'OR', children: [leaf('GT', 500, 'a'), leaf('EQ', 'gold')] })!
    expect(or.yes).not.toBeNull()
    expect(or.no!.filter((r) => r.bads.length).length).toBe(2)
    const not = buildExamples({ op: 'NOT', children: [leaf('EQ', 'gold')] })!
    expect(not.yes![0].value).not.toBe('gold')
    expect(not.no![0].value).toBe('gold')
    expect(buildExamples({ op: 'NOT', children: [leaf('EQ', 'a'), leaf('EQ', 'b')] })!.yes).toBeNull()
    expect(buildExamples({ op: 'OR', children: [leaf('IS_NULL'), leaf('IS_NOT_NULL')] })!.no).toBeNull()
    expect(buildExamples({ op: 'AND', children: [] })).toBeNull()
    expect(buildExamples({ op: 'AND', children: Array.from({ length: 11 }, (_, i) => leaf('EQ', 'x', `f${i}`)) })!.tooMany).toBe(true)
  })
})

describe('buildExamples：有解就找得到、無解才說矛盾', () => {
  const yesOf = (root: Cond) => buildExamples(root)!.yes
  const val = (rows: NonNullable<ReturnType<typeof yesOf>>) => rows[0].value
  it.each([
    ['常數之間 GT 500 且 LT 520', { op: 'AND', children: [leaf('GT', 500, 'a'), leaf('LT', 520, 'a')] }],
    ['常數之間 GT 0 且 LT 1（小數）', { op: 'AND', children: [leaf('GT', 0, 'a'), leaf('LT', 1, 'a')] }],
    ['等於邊界 GTE 500 且 LTE 500', { op: 'AND', children: [leaf('GTE', 500, 'a'), leaf('LTE', 500, 'a')] }],
    ['避開兩個值 NEQ silver 且 NEQ gold', { op: 'AND', children: [leaf('NEQ', 'silver', 'a'), leaf('NEQ', 'gold', 'a')] }],
    ['IN 與 NEQ 組合', { op: 'AND', children: [leaf('IN', ['gold', 'vip'], 'a'), leaf('NEQ', 'gold', 'a')] }],
    ['NOT_IN 兩個再 NEQ', { op: 'AND', children: [leaf('NOT_IN', ['gold', 'vip'], 'a'), leaf('NEQ', 'silver', 'a')] }],
  ] as Array<[string, Cond]>)('%s：有會符合的客戶且判定為成立', (_n, root) => {
    const rows = yesOf(root)
    expect(rows).not.toBeNull()
    expect(evalTree(root, () => val(rows!))).toBe(true)
  })
  it('GTE 500 且 LTE 500：會符合的是 500', () => {
    expect(val(yesOf({ op: 'AND', children: [leaf('GTE', 500, 'a'), leaf('LTE', 500, 'a')] })!)).toBe(500)
  })
  it.each([
    ['包含 a 且 包含 b', ['a', 'b']],
    ['包含 ab 且 包含 bc（需 abc）', ['ab', 'bc']],
    ['包含 a、b、c', ['a', 'b', 'c']],
    ['包含 a、b、c、d、e（超過 4 個，正序串接）', ['a', 'b', 'c', 'd', 'e']],
  ])('同欄位多個「%s」：有會符合的客戶', (_n, subs) => {
    const root: Cond = { op: 'AND', children: subs.map((x) => leaf('CONTAINS', x, 'a')) }
    const rows = yesOf(root)
    expect(rows).not.toBeNull()
    expect(evalTree(root, () => val(rows!))).toBe(true)
  })
  it('真矛盾仍回 null：EQ gold 且 CONTAINS zzz', () => {
    expect(yesOf({ op: 'AND', children: [leaf('EQ', 'gold', 'a'), leaf('CONTAINS', 'zzz', 'a')] })).toBeNull()
  })
  it('數字欄 NOT_IN 加範圍：不會符合那位只有一條不合、值不是文字', () => {
    for (const children of [[leaf('NOT_IN', [5, 6, 7], 'a'), leaf('GT', 4, 'a'), leaf('LT', 8, 'a')], [leaf('NOT_IN', [5, 6, 7], 'a'), leaf('GT', 4, 'a')]]) {
      const e = buildExamples({ op: 'AND', children })!
      expect(typeof e.no![0].value).toBe('number')
      expect(e.no![0].bads.length).toBe(1)
      expect(e.yes![0].value).not.toBeUndefined()
    }
  })
  const noBads = (root: Cond) => buildExamples(root)!.no!.flatMap((r) => r.bads).length
  it.each([
    ['qa1 包含 a、b、c', ['a', 'b', 'c']],
    ['qa2 包含 a、ab、b', ['a', 'ab', 'b']],
    ['qa3 包含 ab、bc、a、x', ['ab', 'bc', 'a', 'x']],
    ['包含 a 且 包含 ab', ['a', 'ab']],
  ])('%s：不會符合那位只有 1 條紅字原因且判定為不成立', (_n, subs) => {
    const root: Cond = { op: 'AND', children: subs.map((x) => leaf('CONTAINS', x, 'a')) }
    const e = buildExamples(root)!
    expect(e.tooMany).toBeUndefined()
    expect(noBads(root)).toBe(1)
    expect(evalTree(root, () => e.no![0].value)).toBe(false)
  })
  it('同欄位 2–4 條（CONTAINS／EQ／NEQ／IN／NOT_IN）：不合條數不多於字母表 {a,b,c,x} 長度 ≤ 8 暴力窮舉的最少條數', () => {
    const alpha = ['a', 'b', 'c', 'x']
    const all: Array<string | undefined> = [undefined]
    let layer = ['']
    for (let n = 0; n <= 8; n++) {
      if (n > 0) layer = layer.flatMap((t) => alpha.map((c) => t + c))
      all.push(...layer)
    }
    const consts = ['a', 'b', 'c', 'ab', 'bc', 'x', 'xb']
    const C = (v: string) => leaf('CONTAINS', v, 'a')
    const cases: CondLeaf[][] = []
    for (let i = 0; i < consts.length; i++)
      for (let j = i + 1; j < consts.length; j++) {
        cases.push([C(consts[i]), C(consts[j])])
        for (let k = j + 1; k < consts.length; k++) cases.push([C(consts[i]), C(consts[j]), C(consts[k])])
      }
    cases.push(
      [C('ab'), C('bc'), C('a'), C('x')], [C('a'), C('b'), C('c'), C('x')], [C('xb'), C('b'), C('x'), C('c')], [C('a'), C('ab'), C('b')],
      [C('a'), C('b'), leaf('NEQ', 'a', 'a')], [C('a'), C('b'), C('c'), leaf('NOT_IN', ['a', 'b'], 'a')], [C('b'), leaf('IN', ['ab', 'x'], 'a')],
      [C('a'), C('b'), leaf('IN', ['ab', 'xb'], 'a')], [C('c'), C('x'), leaf('NEQ', 'x', 'a'), leaf('NEQ', 'c', 'a')], [leaf('EQ', 'ab', 'a'), C('a')],
      [leaf('NEQ', 'a', 'a'), leaf('NEQ', 'b', 'a'), C('c')], [leaf('NOT_IN', ['a', 'b'], 'a'), leaf('NOT_IN', ['bc'], 'a'), C('b')],
      [leaf('IN', ['a', 'ab', 'xb'], 'a'), C('b'), leaf('NEQ', 'ab', 'a')],
    )
    for (const ls of cases) {
      const root: Cond = { op: 'AND', children: ls }
      let min = Infinity
      for (const v of all) {
        const bad = ls.filter((l) => !evalLeaf(v, l.operator, l.value)).length
        if (bad > 0) min = Math.min(min, bad)
      }
      const e = buildExamples(root)!
      const label = JSON.stringify(ls.map((l) => [l.operator, l.value]))
      expect(e.tooMany, label).toBeUndefined()
      expect(e.yes, label).not.toBeNull()
      expect(noBads(root), label).toBeGreaterThanOrEqual(1)
      expect(noBads(root), label).toBeLessThanOrEqual(min)
    }
  })
  it('NOT 底下的條件：不會符合那位標出原因', () => {
    const one = buildExamples({ op: 'NOT', children: [leaf('EQ', 'gold')] })!
    expect(one.no![0].bads).toEqual(['不合：不可以是等於 gold'])
    const mixed = buildExamples({ op: 'AND', children: [leaf('GT', 500, 'amt'), { op: 'NOT', children: [leaf('EQ', 'gold')] }] })!
    expect(mixed.no!.flatMap((r) => r.bads).length).toBe(1)
  })
  it('同欄位重複說明只列一次', () => {
    const e = buildExamples({ op: 'AND', children: [leaf('GTE', 500, 'a'), leaf('LTE', 500, 'a')] })!
    expect(e.yes![0].notes).toEqual(['剛好等於也算'])
  })

  // 小型窮舉對照：單一欄位、一到兩個條件（AND／OR／NOT），與稠密取樣的「真實有沒有解」比對
  const numLeaves: CondLeaf[] = [
    ...['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NEQ'].flatMap((op) => [0, 1, 500, 520].map((v) => leaf(op, v, 'a'))),
    leaf('IN', [0, 1], 'a'), leaf('IN', [500, 520], 'a'), leaf('NOT_IN', [0, 1], 'a'), leaf('NOT_IN', [500, 520], 'a'),
    leaf('IS_NULL', undefined, 'a'), leaf('IS_NOT_NULL', undefined, 'a'),
  ]
  const textLeaves: CondLeaf[] = [
    ...['EQ', 'NEQ', 'CONTAINS'].flatMap((op) => ['gold', 'vip', 'silver'].map((v) => leaf(op, v, 'a'))),
    leaf('IN', ['gold', 'vip'], 'a'), leaf('IN', ['vip', 'silver'], 'a'), leaf('NOT_IN', ['gold', 'vip'], 'a'), leaf('NOT_IN', ['vip', 'silver'], 'a'),
    leaf('IS_NULL', undefined, 'a'), leaf('IS_NOT_NULL', undefined, 'a'),
  ]
  const trees = (ls: CondLeaf[]): Cond[] => [
    ...ls.map((a): Cond => ({ op: 'NOT', children: [a] })),
    ...ls.flatMap((a) =>
      ls.flatMap((b): Cond[] => [
        { op: 'AND', children: [a, b] },
        { op: 'OR', children: [a, b] },
        { op: 'AND', children: [a, { op: 'NOT', children: [b] }] },
      ]),
    ),
  ]
  const check = (ls: CondLeaf[], domain: unknown[]) => {
    for (const root of trees(ls)) {
      const truth = domain.map((v) => evalTree(root, () => v))
      const e = buildExamples(root)!
      const label = JSON.stringify(root)
      expect(e.yes !== null, `yes ${label}`).toBe(truth.some(Boolean))
      expect(e.no !== null, `no ${label}`).toBe(truth.some((t) => !t))
      if (e.yes) expect(evalTree(root, () => e.yes![0].value), `yes值 ${label}`).toBe(true)
      if (e.no) expect(evalTree(root, () => e.no![0].value), `no值 ${label}`).toBe(false)
    }
  }
  it('數字欄：結論與稠密取樣一致', () => {
    const domain: unknown[] = [undefined]
    for (let x = -3; x <= 524; x += 0.25) domain.push(x)
    check(numLeaves, domain)
  })
  it('包含系列與 IN／NOT_IN／EQ 混用：結論與全部短字串取樣一致', () => {
    const ls: CondLeaf[] = [
      ...['a', 'b', 'ab', 'ba'].map((v) => leaf('CONTAINS', v, 'a')),
      leaf('IN', ['ab', 'x'], 'a'), leaf('NOT_IN', ['a'], 'a'), leaf('NOT_IN', ['ab', 'b'], 'a'), leaf('EQ', 'a', 'a'), leaf('NEQ', 'ab', 'a'),
    ]
    const domain: unknown[] = [undefined]
    const alpha = ['a', 'b', 'x']
    let cur = ['']
    for (let n = 0; n < 4; n++) {
      cur = cur.flatMap((t) => alpha.map((c) => t + c))
      domain.push(...cur)
    }
    check(ls, domain)
  })
  it('文字欄：結論與取樣一致', () => {
    const base = ['gold', 'vip', 'silver']
    const domain: unknown[] = [undefined, ...base, 'bronze', 'gold_plus', 'vip_plus', 'silver_plus', 'zzz', 'a gold b']
    // 包含多個常數的字串：兩兩與三個串接（各種順序）
    for (const x of base) for (const y of base) if (x !== y) domain.push(x + y, ...base.filter((z) => z !== x && z !== y).map((z) => x + y + z))
    check(textLeaves, domain)
  })
})

describe('比較選單文字與分組（canvas-interaction §3）', () => {
  it('11 個比較依序、符號在全形括號內；model.OPERATORS 同一套文字', () => {
    expect(FILTER_OPS.map((o) => opMenuLabel(o.value))).toEqual(['大於（>）', '至少（≥）', '小於（<）', '至多（≤）', '等於', '不等於', '包含', '是其中之一', '不是其中任何一個', '有填', '沒填'])
    expect(OPERATORS.map((o) => o.label)).toEqual(FILTER_OPS.map((o) => o.menu))
    expect(OPERATORS.filter((o) => o.noValue).map((o) => o.value)).toEqual(['IS_NOT_NULL', 'IS_NULL'])
    expect(opMenuLabel('X')).toBe('X')
  })
  it('分組名與 opsFor：型別已知不列不適用項目', () => {
    expect(OP_GROUPS.map((g) => g.label)).toEqual(['數字比大小', '比對內容', '有沒有填'])
    expect(opsFor('properties.utm_source', '').map((o) => o.value)).not.toContain('GT')
    expect(opsFor('properties.cart_total', '').map((o) => o.value)).not.toContain('IN')
    expect(opsFor('', '').length).toBe(11)
  })
  it('型別不明說明文字', () => {
    expect(unknownKindHint('amount')).toBe('系統不知道『amount』是數字還是文字。要比大小，請在比較欄選『數字比大小』那一組的項目（大於、至少、小於、至多），500 會當成數字比對。')
  })
})
