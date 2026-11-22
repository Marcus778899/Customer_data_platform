// @vitest-environment jsdom
/**
 * canvas-uat-fix：F-44 面板打開時畫布讓出面板寬、F-45 面板開著按「驗證」結果要看得到、F-46 事件篩選比較欄 176px。
 * 規格：docs/ui/canvas-interaction.md §1「面板打開時畫布讓出面板寬（>=1101px），不蓋節點」、§3「比較欄固定 176px」、journey-builder.md。
 * jsdom 不算版面：F-44／F-46 用 condition-compare-ux/layout.ts 的攤平 @media 做法，對真實元素讀 getComputedStyle。
 * F-44 假設（結構）：面板 `.canvas-area > .edside` 不能浮在畫布上（position: absolute／fixed）又不為畫布保留空間；
 *   做法二選一：(a) 面板在文件流中、`.canvas-area` 是 flex／grid 橫排；(b) 面板仍浮著，但 `.canvas-wrap`（或 `.canvas-area`）的
 *   margin-right／padding-right／width（calc(100% - ...)）預留 >= 面板寬 360px。面板關閉（hidden）時畫布要回到整寬，不預留。
 * F-45 假設：按「驗證」後，驗證結果面板（aside，h2「驗證結果」）要是 `.edside` 裡第一個面板（取代或排到最上），
 *   或自動捲動（Element.scrollIntoView 以該面板或其內元素呼叫）。
 * F-46 假設：進入事件／等事件的篩選列比較下拉（aria-label「比較」）寬 176px（width／flex-basis 為 176px，或欄位用 grid 第一欄 176px）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { DRAFT_PATH, cleanup, editorBackend, json, openEditor, resetEnv, screen, selectNode, teardown, userEvent, waitFor, within } from '../journey-canvas-ui/fixtures'
import { and, efBackend, efDef, leaf, open } from '../event-filter-canvas-ui/fixtures'
import { fixedWidth, withViewport } from '../condition-compare-ux/layout'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const PANEL = 360
const px = (s: string) => {
  const m = /^(-?[\d.]+)px$/.exec(s.trim())
  return m ? Number(m[1]) : 0
}
/** 一個樣式值裡所有 px 數字加總（calc(100% - 372px) 之類） */
const calcPx = (s: string) => [...s.matchAll(/(-?[\d.]+)px/g)].reduce((a, m) => a + Math.abs(Number(m[1])), 0)

function reservedRight(): { inflow: boolean; reserved: number } {
  const side = document.querySelector<HTMLElement>('.canvas-area > .edside')!
  const area = side.parentElement!
  const wrap = area.querySelector<HTMLElement>('.canvas-wrap')!
  const sc = getComputedStyle(side)
  if (sc.display === 'none' || side.hidden) return { inflow: false, reserved: 0 }
  const ac = getComputedStyle(area)
  const inflow = !/absolute|fixed/.test(sc.position) && /flex|grid/.test(ac.display) && ac.flexDirection !== 'column'
  const wc = getComputedStyle(wrap)
  const widthCalc = /calc\(\s*100%/.test(wc.width) ? calcPx(wc.width) : 0
  const maxCalc = /calc\(\s*100%/.test(wc.maxWidth) ? calcPx(wc.maxWidth) : 0
  return { inflow, reserved: Math.max(px(wc.marginRight), px(wc.paddingRight), px(ac.paddingRight), widthCalc, maxCalc) }
}

describe('F-44 面板打開時畫布讓出面板寬（>= 1101px）', () => {
  it.each([1440, 1101])('%ipx：選取節點開面板後，面板不蓋在畫布上（在文件流中，或畫布預留 >= 360px）', async (w) => {
    editorBackend()
    await openEditor()
    await selectNode('是 VIP？')
    await screen.findByRole('complementary', { name: /是 VIP？/ })
    withViewport(w, () => {
      const side = document.querySelector<HTMLElement>('.canvas-area > .edside')
      expect(side, '面板容器 .canvas-area > .edside').toBeTruthy()
      const r = reservedRight()
      expect(r.inflow || r.reserved >= PANEL, `面板仍浮著（position ${getComputedStyle(side!).position}），畫布右側預留 ${r.reserved}px`).toBe(true)
    })
  })

  it('旅程設定面板開著時也一樣讓出寬度', async () => {
    editorBackend()
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '旅程設定' }))
    await screen.findByLabelText('旅程名稱')
    withViewport(1440, () => {
      const r = reservedRight()
      expect(r.inflow || r.reserved >= PANEL, `預留 ${r.reserved}px`).toBe(true)
    })
  })

  it('面板關閉後畫布回到整寬（不預留空位）', async () => {
    editorBackend()
    await openEditor()
    await selectNode('是 VIP？')
    const panel = await screen.findByRole('complementary', { name: /是 VIP？/ })
    await userEvent.click(within(panel).getByRole('button', { name: /關閉/ }))
    await waitFor(() => expect(screen.queryByRole('complementary', { name: /是 VIP？/ })).toBeNull())
    withViewport(1440, () => {
      expect(reservedRight().reserved, '關閉後不應預留空位').toBeLessThan(100)
    })
  })
})

describe('F-45 面板開著按「驗證」，結果要在看得到的位置', () => {
  const VALIDATE = `POST ${DRAFT_PATH}/validate`
  const warnOnly = () =>
    json({ valid: true, errors: [], warnings: [{ field: 'definition.settings.goal', message: '沒設目標', rule: null, node_id: null }] })
  const panelsIn = (side: HTMLElement) => [...side.children].filter((c) => c.tagName === 'ASIDE' && !(c as HTMLElement).hidden)

  async function validateWith(openIt: () => Promise<void>) {
    const scrolled: Element[] = []
    const orig = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function (this: Element) { scrolled.push(this) }
    try {
      editorBackend({ routes: { [VALIDATE]: warnOnly } })
      await openEditor()
      await openIt()
      await userEvent.click(screen.getByRole('button', { name: '驗證' }))
      const heading = await screen.findByRole('heading', { name: '驗證結果' })
      const panel = heading.closest('aside')!
      const side = panel.parentElement!
      await waitFor(() => {
        const first = panelsIn(side)[0] === panel
        const scrolledTo = scrolled.some((el) => panel.contains(el) || el.contains(panel))
        expect(first || scrolledTo, `驗證結果面板在 .edside 裡排第 ${panelsIn(side).indexOf(panel) + 1}，且沒有捲動到它（使用者看不到）`).toBe(true)
      })
    } finally {
      Element.prototype.scrollIntoView = orig
    }
  }

  it('旅程設定面板開著按「驗證」：結果取代設定面板、排到最上或自動捲到', async () => {
    await validateWith(async () => {
      await userEvent.click(screen.getByRole('button', { name: '旅程設定' }))
      await screen.findByLabelText('旅程名稱')
    })
  })

  it('節點面板開著按「驗證」：同樣看得到結果', async () => {
    await validateWith(async () => {
      await selectNode('是 VIP？')
      await screen.findByRole('complementary', { name: /是 VIP？/ })
    })
  })

  it('沒有面板時按「驗證」仍顯示結果（不退步）', async () => {
    editorBackend({ routes: { [VALIDATE]: warnOnly } })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    expect(await screen.findByText(/沒有錯誤，可以送審，另 1 個提醒/)).toBeTruthy()
  })
})

describe('F-46 事件篩選的比較欄寬 176px（與條件節點一致）', () => {
  const is176 = (cmp: HTMLElement) => {
    const grid = getComputedStyle(cmp.parentElement!).gridTemplateColumns
    return {
      ok: fixedWidth(cmp) === 176 || (getComputedStyle(cmp).width === '100%' && /^176px\b/.test(grid)),
      note: `比較欄寬 ${getComputedStyle(cmp).width}（父層 grid-template-columns: ${grid}），規格 176px`,
    }
  }

  it.each([1440, 1024, 768])('%ipx：進入事件的篩選列，比較下拉固定 176px', async (w) => {
    efBackend(efDef(and(leaf('properties.cart_total', 'GT', 500))))
    const group = await open('加入購物車')
    const cmp = group.querySelector<HTMLSelectElement>('select[aria-label="比較"]')
    expect(cmp, '篩選列的比較下拉').toBeTruthy()
    withViewport(w, () => {
      const r = is176(cmp!)
      expect(r.ok, r.note).toBe(true)
    })
  })

  it('等事件節點的篩選列同樣 176px', async () => {
    efBackend(efDef(null, and(leaf('properties.order_total', 'GTE', 500))))
    const group = await open('等結帳 24h', /^只算符合條件的事件/)
    const cmp = group.querySelector<HTMLSelectElement>('select[aria-label="比較"]')!
    withViewport(1440, () => {
      const r = is176(cmp)
      expect(r.ok, r.note).toBe(true)
    })
  })
})
