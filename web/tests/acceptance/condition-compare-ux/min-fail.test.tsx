// @vitest-environment jsdom
/**
 * condition-compare-ux：不會符合那位「只讓一條不合」的最少條數（仲裁 5e9c101 第 4 條）。
 * 範圍：同欄位 2–4 條、運算子 CONTAINS／EQ／NEQ／IN／NOT_IN、AND 群組、單一欄位；
 * 畫面上不會符合那位的紅字原因條數，不可多於暴力窮舉（字母表 a b c x、長度 ≤ 8，加上沒填）得到的最少不合條數。
 * 不要求：n > 4 個 CONTAINS、OR／NOT 底下、要同時改兩個欄位、數字欄。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, resetEnv, teardown } from '../journey-canvas-ui/fixtures'
import { andOf, openCond, tierLeaf, tryBox } from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

type L = { op: string; v: unknown }
// 契約 §4：缺值一律不成立；EQ／IN 以值相等；CONTAINS 為字串 includes
const holds = (s: string | undefined, { op, v }: L): boolean => {
  if (s === undefined) return false
  switch (op) {
    case 'CONTAINS': return s.includes(v as string)
    case 'EQ': return s === v
    case 'NEQ': return s !== v
    case 'IN': return (v as string[]).includes(s)
    case 'NOT_IN': return !(v as string[]).includes(s)
    default: throw new Error(op)
  }
}
const ALPHA = ['a', 'b', 'c', 'x']
const strings = (): Array<string | undefined> => {
  const out: Array<string | undefined> = [undefined, '']
  let layer = ['']
  for (let n = 1; n <= 8; n++) {
    layer = layer.flatMap((p) => ALPHA.map((ch) => p + ch))
    out.push(...layer)
  }
  return out
}
const ALL = strings()
/** [能讓整個 AND 成立的字串是否存在, 讓 AND 不成立時最少的不合條數] */
function brute(ls: L[]) {
  let yes = false
  let min = Infinity
  for (const s of ALL) {
    const bad = ls.filter((l) => !holds(s, l)).length
    if (bad === 0) yes = true
    else min = Math.min(min, bad)
  }
  return { yes, min }
}

const C = (v: string): L => ({ op: 'CONTAINS', v })
const E = (v: string): L => ({ op: 'EQ', v })
const N = (v: string): L => ({ op: 'NEQ', v })
const I = (v: string[]): L => ({ op: 'IN', v })
const NI = (v: string[]): L => ({ op: 'NOT_IN', v })

const CASES: Array<[string, L[]]> = [
  ['qa1：包含 a、b、c', [C('a'), C('b'), C('c')]],
  ['qa2：包含 a、ab、b', [C('a'), C('ab'), C('b')]],
  ['qa3：包含 ab、bc、a、x', [C('ab'), C('bc'), C('a'), C('x')]],
  ['包含 a 且 包含 ab（a 只有 ab 不合）', [C('a'), C('ab')]],
  ['包含 a、b', [C('a'), C('b')]],
  ['包含 ab、bc', [C('ab'), C('bc')]],
  ['包含 a、b、c、x', [C('a'), C('b'), C('c'), C('x')]],
  ['包含 a、b、bc', [C('a'), C('b'), C('bc')]],
  ['包含 xb、b、x、c', [C('xb'), C('b'), C('x'), C('c')]],
  ['包含 a、b 且 不等於 a', [C('a'), C('b'), N('a')]],
  ['包含 a、c 且 不等於 ab', [C('a'), C('c'), N('ab')]],
  ['包含 a、b、c 且 不是其中任何一個 [a, b]', [C('a'), C('b'), C('c'), NI(['a', 'b'])]],
  ['包含 b 且 是其中之一 [ab, x]', [C('b'), I(['ab', 'x'])]],
  ['包含 a、b 且 是其中之一 [ab, xb]', [C('a'), C('b'), I(['ab', 'xb'])]],
  ['包含 c、x 且 不等於 x、不等於 c', [C('c'), C('x'), N('x'), N('c')]],
  ['等於 ab 且 包含 a', [E('ab'), C('a')]],
  ['不等於 a、不等於 b、包含 c', [N('a'), N('b'), C('c')]],
  ['不是其中任何一個 [a, b]、[bc] 且 包含 b', [NI(['a', 'b']), NI(['bc']), C('b')]],
  ['是其中之一 [a, ab, xb] 且 包含 b 且 不等於 ab', [I(['a', 'ab', 'xb']), C('b'), N('ab')]],
  ['包含 a、bc、xb', [C('a'), C('bc'), C('xb')]],
]

describe('不會符合那位的不合條數不多於暴力窮舉的最少條數', () => {
  it.each(CASES)('%s', async (_n, ls) => {
    const truth = brute(ls)
    expect(truth.yes, '測試案例本身要有解').toBe(true)
    const { panel } = await openCond(andOf(...ls.map((l) => tierLeaf(l.op, l.v))))
    const box = tryBox(panel)
    const text = (box.textContent ?? '').replace(/\s+/g, '')
    expect(text).not.toContain('條件太多')
    expect(text).not.toContain('條件互相矛盾')
    const no = box.querySelector('.try-ex .no') as HTMLElement
    expect(no, '要有不會符合的客戶').toBeTruthy()
    const bads = (no.textContent ?? '').replace(/\s+/g, '').match(/不合：/g)?.length ?? 0
    expect(bads).toBeGreaterThanOrEqual(1)
    expect(bads).toBeLessThanOrEqual(truth.min)
  })
})
