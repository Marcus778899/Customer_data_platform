// @vitest-environment jsdom
/**
 * condition-compare-ux：「試試看」條件試算（docs/ui/canvas-interaction.md §3）。
 * 判斷對契約 docs/contracts/journeys.md §4.1 測試向量一致：這裡逐條驗 UI 能表達的向量（樣本是文字；轉型規則見 fixtures.tsx），
 * UI 打不出來的向量（#6 #7 #8 #9 #12 #15 #16 #17 #20）由 worker 的單元測試逐條跑。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, resetEnv, teardown, userEvent, waitFor, within } from '../journey-canvas-ui/fixtures'
import { andOf, cartLeaf, openCond, setSample, tierLeaf, tryBox, verdict } from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const YES = '這位客戶會符合'
const NO = '這位客戶不會符合'

describe('契約 §4.1 測試向量（UI 可表達者）：樣本輸入 → 這位客戶會符合／不會符合', () => {
  // [#, 條件, 樣本文字（'' = 欄位不存在）, 期望成立]
  const V: Array<[number, unknown, string, boolean]> = [
    [1, tierLeaf('GT', 500), '600', true],
    [2, tierLeaf('GT', 500), '500', false],
    [3, tierLeaf('GTE', 500), '500', true],
    [4, tierLeaf('LT', 500), '400', true],
    [5, tierLeaf('EQ', 500), '500.0', true],
    [10, tierLeaf('EQ', 'gold'), 'Gold', false],
    [11, tierLeaf('IN', ['gold', 'vip']), 'gold', true],
    [13, tierLeaf('NOT_IN', ['gold', 'vip']), 'silver', true],
    [14, tierLeaf('CONTAINS', 'world'), 'hello world', true],
    [18, tierLeaf('IS_NULL'), '', true],
    [19, tierLeaf('IS_NULL'), '', true],
    [21, tierLeaf('IS_NOT_NULL'), '', false],
    [22, tierLeaf('NEQ', 'gold'), '', false],
    [23, tierLeaf('NOT_IN', ['gold']), '', false],
    [24, tierLeaf('GT', 500), '', false],
    [25, { op: 'NOT', children: [tierLeaf('EQ', 'gold')] }, '', true],
  ]
  it.each(V)('#%i', async (_n, leaf, sample, expected) => {
    const { panel } = await openCond(andOf(leaf))
    const box = tryBox(panel)
    await setSample(box, '會員等級', sample)
    await waitFor(() => {
      const v = verdict(box)
      expect(v).toContain(expected ? YES : NO)
      if (expected) expect(v).not.toContain(NO)
    })
  })

  it('值是數字時，非數字樣本（abc）比大小不成立；補空白不影響數字（" 600 " 大於 500 成立）', async () => {
    const { panel } = await openCond(andOf(tierLeaf('GT', 500)))
    const box = tryBox(panel)
    await setSample(box, '會員等級', 'abc')
    await waitFor(() => expect(verdict(box)).toContain(NO))
    await setSample(box, '會員等級', ' 600 ')
    await waitFor(() => expect(verdict(box)).toContain(YES))
  })
})

describe('試試看區', () => {
  it('預設展開（details open），面板最下方的區塊，有固定說明（只用樣本、不讀客戶）', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500)))
    const box = tryBox(panel)
    expect(box.open).toBe(true)
    expect(box.textContent).toContain('不會讀取真實客戶')
    const field = within(panel).getAllByLabelText('欄位')[0]
    expect(field.compareDocumentPosition(box) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const remove = within(panel).getAllByRole('button', { name: '移除這個條件' })[0]
    expect(remove.compareDocumentPosition(box) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('一位會符合、一位不會符合的客戶；單一條件時不合那條標「不合：要大於 500（剛好等於不算）」', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500)))
    const t = (tryBox(panel).textContent ?? '').replace(/\s+/g, '')
    expect(t).toContain('一位會符合的客戶')
    expect(t).toContain('一位不會符合的客戶')
    expect(t).toContain('不合：要大於500（剛好等於不算）')
    expect(t).toContain('購物車金額')
  })

  it('至少 500：會符合那位附「剛好等於也算」', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GTE', 500)))
    expect((tryBox(panel).textContent ?? '').replace(/\s+/g, '')).toContain('剛好等於也算')
  })

  it('多條件時不會符合那位只有一條不合', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500), tierLeaf('EQ', 'gold')))
    const t = (tryBox(panel).textContent ?? '').replace(/\s+/g, '')
    expect(t.match(/不合：/g)?.length).toBe(1)
  })

  it('樣本輸入即時判斷並逐條列結果（兩個條件各一條，含欄位名）', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500), tierLeaf('EQ', 'gold')))
    const box = tryBox(panel)
    await setSample(box, '購物車金額', '650')
    await setSample(box, '會員等級', 'gold')
    await waitFor(() => expect(verdict(box)).toContain(YES))
    const status = within(box).getAllByRole('status').find((s) => (s.textContent ?? '').includes(YES))!
    expect(status.querySelectorAll('li').length).toBe(2)
    await setSample(box, '會員等級', 'silver')
    await waitFor(() => expect(verdict(box)).toContain(NO))
    const lis = [...within(box).getAllByRole('status').find((s) => (s.textContent ?? '').includes(NO))!.querySelectorAll('li')]
    expect(lis.length).toBe(2)
    expect(lis[0].textContent).toContain('購物車金額')
    expect(lis[1].textContent).toContain('會員等級')
    expect(lis[1].querySelector('svg')).toBeTruthy()
  })

  it('改條件後試算跟著變（大於 500 改至少 500，樣本 500 由不符合變符合）', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500)))
    const box = tryBox(panel)
    await setSample(box, '購物車金額', '500')
    await waitFor(() => expect(verdict(box)).toContain(NO))
    await userEvent.selectOptions(within(panel).getByLabelText('比較'), 'GTE')
    await waitFor(() => expect(verdict(box)).toContain(YES))
  })

  it('純前端：打樣本與改樣本不產生任何 API 請求', async () => {
    const { ed, panel } = await openCond(andOf(cartLeaf('GT', 500)))
    const box = tryBox(panel)
    await waitFor(() => expect(within(box).getAllByRole('status').length).toBeGreaterThan(0))
    const before = ed.api.calls.length
    await setSample(box, '購物車金額', '700')
    await setSample(box, '購物車金額', '1')
    await new Promise((r) => setTimeout(r, 300))
    expect(ed.api.calls.length).toBe(before)
    expect(ed.puts().length).toBe(0)
  })

  it('有 behavior 葉節點：整區停用並說明不支援', async () => {
    const { panel } = await openCond(andOf(cartLeaf('GT', 500), { type: 'behavior', event: 'order_completed' }))
    const box = tryBox(panel)
    expect(box.textContent).toContain('不支援')
    for (const el of box.querySelectorAll<HTMLInputElement>('input, textarea, select')) expect(el.disabled || el.readOnly || el.closest('fieldset[disabled]')).toBeTruthy()
    expect(within(box).queryAllByRole('status').some((s) => (s.textContent ?? '').includes(YES))).toBe(false)
  })

  it('有 segment 葉節點：同樣停用', async () => {
    const { panel } = await openCond(andOf({ type: 'segment', segment_id: 'seg_1' }))
    const box = tryBox(panel)
    expect(box.textContent).toContain('不支援')
    for (const el of box.querySelectorAll<HTMLInputElement>('input, textarea, select')) expect(el.disabled || el.readOnly || el.closest('fieldset[disabled]')).toBeTruthy()
  })
})

describe('試試看：同欄位多條件的範例（回歸：CONTAINS 誤報矛盾、NOT_IN 只列一條不合）', () => {
  const MSG = '條件互相矛盾'
  const exText = async (cond: unknown) => {
    const { panel } = await openCond(cond)
    return tryBox(panel).textContent ?? ''
  }
  const flat = (s: string) => s.replace(/\s+/g, '')

  it.each([
    ['包含 a 且 包含 b', [tierLeaf('CONTAINS', 'a'), tierLeaf('CONTAINS', 'b')]],
    ['包含 ab 且 包含 bc（需 abc）', [tierLeaf('CONTAINS', 'ab'), tierLeaf('CONTAINS', 'bc')]],
    ['包含 vip 且 包含 gold', [tierLeaf('CONTAINS', 'vip'), tierLeaf('CONTAINS', 'gold')]],
    ['包含 a、b、c 三個', [tierLeaf('CONTAINS', 'a'), tierLeaf('CONTAINS', 'b'), tierLeaf('CONTAINS', 'c')]],
    ['是其中之一 [ab, x] 且 包含 a', [tierLeaf('IN', ['ab', 'x']), tierLeaf('CONTAINS', 'a')]],
    ['不是其中任何一個 [a] 且 包含 a_x', [tierLeaf('NOT_IN', ['a']), tierLeaf('CONTAINS', 'a_x')]],
  ])('%s：有一位會符合的客戶，不顯示條件互相矛盾', async (_n, leaves) => {
    const t = flat(await exText(andOf(...leaves)))
    expect(t).toContain('一位會符合的客戶')
    expect(t).not.toContain(MSG)
  })

  it('真矛盾（等於 gold 且 包含 zzz）仍顯示條件互相矛盾', async () => {
    const t = flat(await exText(andOf(tierLeaf('EQ', 'gold'), tierLeaf('CONTAINS', 'zzz'))))
    expect(t).toContain(MSG)
  })

  it.each([
    ['NOT_IN [5,6,7] 且 大於 4 且 小於 8', [cartLeaf('NOT_IN', [5, 6, 7]), cartLeaf('GT', 4), cartLeaf('LT', 8)]],
    ['NOT_IN [5,6,7] 且 大於 4', [cartLeaf('NOT_IN', [5, 6, 7]), cartLeaf('GT', 4)]],
  ])('數字欄 %s：不會符合那位只有一條紅字原因', async (_n, leaves) => {
    const { panel } = await openCond(andOf(...leaves))
    const box = tryBox(panel)
    const no = box.querySelector('.try-ex .no') as HTMLElement
    expect(no).toBeTruthy()
    expect(flat(no.textContent ?? '').match(/不合：/g)?.length).toBe(1)
    expect(no.querySelectorAll('dd.fail .why').length).toBe(1)
    expect(no.querySelectorAll('dd.fail').length).toBe(1)
  })
})
