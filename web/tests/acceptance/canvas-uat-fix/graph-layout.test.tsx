// @vitest-environment jsdom
/**
 * canvas-uat-fix：F-51 畫布存過 layout 位置的旅程，旅程頁與軌跡頁的節點圖（docs/ui/canvas-interaction.md §2、uat v0.3.0 第八輪 F-51）。
 * 重現：畫布上拖放的節點位置（可為負座標、離原點很遠）存進 layout；唯讀圖用它排版，框高被量成約 136～155px、
 * 卡片上緣被切掉、「看全圖」沒效果、提示寫「已看到全部」卻與畫面不符。
 * 預期：不論 layout 位置怎麼擺，節點圖座標都正規化到「左上角留邊距」，框高涵蓋整張圖、看全圖有效、提示與實際一致；
 * layout 為 null（demo-seed）不退步。容器寬與讀法同 node-card-unify/helpers（GRAPH_W 1160）。
 * 假設：唯讀圖 `.graph` 的第一個子 div 是尺寸外框（style.height ＝ 圖高 × 縮放），`.inner` 的 style 寬高是圖的流程座標範圍。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, screen, waitFor } from '@testing-library/react'
import { API, STATS3, branchVersion, json, nodeStats, openJourney, openTrace, resetEnv, teardown, userEvent } from '../trace-branch-ui/fixtures'
import { GRAPH_W, graphEl, hintText, mockGraphWidth, readBoxes, readPercent, restoreGraphWidth, waitForPercent } from '../node-card-unify/helpers'

beforeEach(() => {
  resetEnv()
  mockGraphWidth(GRAPH_W)
})
afterEach(() => {
  cleanup()
  teardown()
  restoreGraphWidth()
})

/** 畫布存的位置：條件分兩路再合流，迴圈在最右；整體平移 (dx, dy)。「開始／進入條件」在下排、其餘在上排（uat 情境） */
const CANVAS_BASE: Record<string, [number, number]> = {
  start: [0, 220], src_1: [260, 220], wait_1: [520, 0], cond_vip: [780, 0], send_vip: [1040, -160], send_normal: [1040, 160],
  merge_1: [1300, 0], wait_ev: [1560, 0], goal_1: [1820, -160], send_last: [1820, 160],
}
const canvasLayout = (dx: number, dy: number) => ({
  nodes: Object.fromEntries(Object.entries(CANVAS_BASE).map(([id, [x, y]]) => [id, { x: x + dx, y: y + dy }])),
})
const CASES: Array<[string, unknown]> = [
  ['畫布存了負座標（往左上拖過原點）', canvasLayout(-300, -240)],
  ['畫布存的位置離原點很遠（往右下拖）', canvasLayout(2400, 1800)],
  ['demo-seed：layout 為 null', null],
]

const ready = async () => {
  await waitFor(() => expect(readBoxes().length).toBe(10))
  await waitForPercent()
}
const openJ = (layout: unknown) =>
  openJourney({
    [`GET ${API}/versions/3`]: () => json(branchVersion(3, { layout })),
    [`GET ${STATS3}`]: () => json(nodeStats()),
  })
const openT = (layout: unknown) => openTrace(undefined, branchVersion(3, { layout }))

const frameHeight = () => Number.parseFloat((graphEl()!.firstElementChild as HTMLElement).style.height)
const inner = () => graphEl()!.querySelector<HTMLElement>('.inner')!
const num = (v: string) => Number.parseFloat(v)

const PAGES: Array<[string, (layout: unknown) => void]> = [
  ['旅程頁', openJ],
  ['軌跡頁', openT],
]

describe.each(PAGES)('%s：畫布存過 layout 的旅程', (_page, open) => {
  describe.each(CASES)('%s', (_name, layout) => {
    it('每張卡都在圖的範圍內（座標 >= 0、不超出 .inner），左上角靠近原點', async () => {
      open(layout)
      await ready()
      const bs = readBoxes()
      const w = num(inner().style.width)
      const h = num(inner().style.height)
      expect(bs.filter((b) => b.x < 0 || b.y < 0).map((b) => `${b.name} (${b.x},${b.y})`), '卡片座標不可為負（上緣、左緣被切掉）').toEqual([])
      expect(Math.max(...bs.map((b) => b.x + b.w)), '圖寬涵蓋所有卡').toBeLessThanOrEqual(w + 0.5)
      expect(Math.max(...bs.map((b) => b.y + b.h)), '圖高涵蓋所有卡').toBeLessThanOrEqual(h + 0.5)
      expect(Math.min(...bs.map((b) => b.x)), '最左的卡靠近左緣（不留一大片空白）').toBeLessThanOrEqual(40)
      expect(Math.min(...bs.map((b) => b.y)), '最上的卡靠近上緣').toBeLessThanOrEqual(40)
    })

    it('外框高度涵蓋整張圖（縮放後），不是 136～155px 的細縫', async () => {
      open(layout)
      await ready()
      const z = readPercent()! / 100
      const bs = readBoxes()
      const need = Math.max(...bs.map((b) => b.y + b.h)) * z
      expect(frameHeight(), `外框高 ${frameHeight()}，圖需要 ${Math.round(need)}`).toBeGreaterThanOrEqual(need - 0.5)
      expect(frameHeight()).toBeGreaterThanOrEqual(240)
    })

    it('初始縮放 75%～100%；提示與畫面一致（說「已看到全部」就真的全在容器內）', async () => {
      open(layout)
      await ready()
      const z = readPercent()! / 100
      expect(z).toBeGreaterThanOrEqual(0.75 - 0.005)
      expect(z).toBeLessThanOrEqual(1)
      const bs = readBoxes()
      const allIn = bs.every((b) => b.x >= 0 && (b.x + b.w) * z <= GRAPH_W + 0.5)
      if (/已看到全部/.test(hintText())) expect(allIn, `提示說已看到全部，但有卡在容器之外：${hintText()}`).toBe(true)
      else expect(allIn, `有卡在容器外，提示應是「右邊還有 N 個節點」：${hintText()}`).toBe(false)
    })

    it('按「看全圖」：整張圖進容器（左右都不被切）、提示「已看到全部 10 個節點」', async () => {
      open(layout)
      await ready()
      await userEvent.click(screen.getByRole('button', { name: '看全圖' }))
      await waitFor(() => expect(hintText()).toMatch(/已看到全部\s*10\s*個節點/))
      const z = readPercent()! / 100
      const bs = readBoxes()
      expect(Math.min(...bs.map((b) => b.x)) * z, '左緣不為負').toBeGreaterThanOrEqual(0)
      expect(Math.max(...bs.map((b) => b.x + b.w)) * z, '右緣在容器內').toBeLessThanOrEqual(GRAPH_W + 0.5)
      expect(frameHeight()).toBeGreaterThanOrEqual(Math.max(...bs.map((b) => b.y + b.h)) * z - 0.5)
    })
  })
})

describe('圖形結構不因 layout 改變（只平移，不變形）', () => {
  it('旅程頁：負座標 layout 的欄數、列數與存的位置一致', async () => {
    openJ(canvasLayout(-300, -240))
    await ready()
    const a = readBoxes()
    const dx = Math.min(...a.map((b) => b.x))
    const dy = Math.min(...a.map((b) => b.y))
    const want = Object.values(CANVAS_BASE)
    const mx = Math.min(...want.map(([x]) => x))
    const my = Math.min(...want.map(([, y]) => y))
    expect(new Set(a.map((b) => Math.round(b.x - dx))), '各欄相對位置').toEqual(new Set(want.map(([x]) => x - mx)))
    expect(new Set(a.map((b) => Math.round(b.y - dy))), '各列相對位置').toEqual(new Set(want.map(([, y]) => y - my)))
  })
})
