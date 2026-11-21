// @vitest-environment jsdom
/**
 * node-card-unify：旅程頁／軌跡圖的初始縮放、縮放列與「右邊還有 N 個節點」提示（docs/ui/canvas-interaction.md §2、F-35）。
 * 規則：整張放得下 → 100%；放不下 → 縮到放得下但不低於 75%，並微調到右緣不切在卡片文字中間；右緣 48px 漸層，捲到底消失；
 * 縮放列（− ％ ＋ 看全圖）同一排放提示，捲到底／看全圖後改「已看到全部 N 個節點」；看全圖低於 70% 百分比轉警告色。
 * 容器寬固定 1160（1440 寬螢幕的內容寬），假設見 helpers.tsx。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, screen, waitFor } from '@testing-library/react'
import { STATS3, branchVersion, json, nodeStats, openJourney, openTrace, resetEnv, teardown, userEvent, API } from '../trace-branch-ui/fixtures'
import { GRAPH_W, cutThroughText, fadeVisible, graphEl, hintEl, hintText, mockGraphWidth, percentEl, readBoxes, readPercent, restoreGraphWidth, scrollGraphTo, waitForPercent, zoomGroup } from './helpers'

const wide = (w: number) => {
  restoreGraphWidth()
  mockGraphWidth(w)
}

beforeEach(() => {
  resetEnv()
  mockGraphWidth(GRAPH_W)
})
afterEach(() => {
  cleanup()
  teardown()
  restoreGraphWidth()
})

const ready = async (n = 10) => {
  await waitFor(() => expect(readBoxes().length).toBe(n))
  await waitForPercent()
}

/** 三個節點的短旅程，整張放得下 1160 */
const short = () => ({
  ...branchVersion(3),
  nodes: [
    { id: 'start', type: 'StartNode', name: '開始', config: {}, outputs: { next: 'src_1' } },
    { id: 'src_1', type: 'EventSourceNode', name: '加入購物車', config: { event_name: 'cart_item_added' }, outputs: { next: 'end_1' } },
    { id: 'end_1', type: 'EndNode', name: '結束', config: {}, outputs: {} },
  ],
})
const shortStats = () => nodeStats(3, [
  { node_id: 'src_1', entered: 5, visits: 5, current: 1, out_ports: { next: 5 } },
  { node_id: 'end_1', entered: 4, visits: 4, current: 0, out_ports: {} },
])

describe('旅程頁：縮放列', () => {
  it('有縮放列：縮小、放大、看全圖與百分比；提示在同一個父元素內、不在圖上', async () => {
    openJourney()
    await ready()
    const g = zoomGroup()!
    for (const name of ['縮小', '放大', '看全圖']) expect(screen.getByRole('button', { name }), name).toBeTruthy()
    expect(graphEl()!.contains(g), '縮放列不浮在圖上').toBe(false)
    expect(hintEl(), '提示要在縮放列同一排（同父元素、role=status）').not.toBeNull()
    expect(graphEl()!.contains(hintEl()!)).toBe(false)
  })

  it('放大／縮小會改百分比', async () => {
    openJourney()
    await ready()
    const p0 = readPercent()!
    await userEvent.click(screen.getByRole('button', { name: '放大' }))
    await waitFor(() => expect(readPercent()!).toBeGreaterThan(p0))
    const p1 = readPercent()!
    await userEvent.click(screen.getByRole('button', { name: '縮小' }))
    await userEvent.click(screen.getByRole('button', { name: '縮小' }))
    await waitFor(() => expect(readPercent()!).toBeLessThan(p1))
  })
})

describe('旅程頁：初始縮放', () => {
  it('放不下（8 欄約 1850px 寬）：縮放落在 75%～100%，從開始靠左，右緣不切在卡片文字中間', async () => {
    openJourney()
    await ready()
    const z = readPercent()! / 100
    expect(z).toBeGreaterThanOrEqual(0.75 - 0.005)
    expect(z).toBeLessThanOrEqual(1)
    const bs = readBoxes()
    expect(Math.min(...bs.map((b) => b.x)), '開始節點靠左').toBeLessThanOrEqual(40)
    expect(Math.max(...bs.map((b) => b.x + b.w)) * z, '前置條件：圖比容器寬').toBeGreaterThan(GRAPH_W)
    expect(cutThroughText(bs, GRAPH_W / z, z)).toEqual([])
  })

  it('整張放得下（3 個節點）：100%', async () => {
    openJourney({
      [`GET ${API}/versions/3`]: () => json(short()),
      [`GET ${STATS3}`]: () => json(shortStats()),
    })
    await ready(3)
    expect(readPercent()).toBe(100)
  })

  it('手機寬（內容寬 343）：不低於 75%、上限 125%，右緣不切在卡片文字中間', async () => {
    wide(343)
    openJourney()
    await ready()
    const z = readPercent()! / 100
    expect(z).toBeGreaterThanOrEqual(0.75 - 0.005)
    expect(z).toBeLessThanOrEqual(1.25 + 0.005)
    expect(cutThroughText(readBoxes(), 343 / z, z)).toEqual([])
  })
})

describe('旅程頁：右邊還有 N 個節點', () => {
  it('提示「右邊還有 N 個節點，左右滑動或按『看全圖』」；N 對得上右緣之外的節點數', async () => {
    openJourney()
    await ready()
    const z = readPercent()! / 100
    const m = /右邊還有\s*(\d+)\s*個節點，左右滑動或按[「『]看全圖[」』]/.exec(hintText())
    expect(m, `提示文字：${hintText()}`).not.toBeNull()
    const n = Number(m![1])
    const right = GRAPH_W / z
    const bs = readBoxes()
    expect(n).toBeGreaterThanOrEqual(bs.filter((b) => b.x > right + 15).length)
    expect(n).toBeLessThanOrEqual(bs.filter((b) => b.x + b.w > right - 15).length)
    expect(n).toBeGreaterThanOrEqual(1)
    expect(n).toBeLessThan(bs.length)
  })

  it('右緣有 48px 漸層；捲到最右邊後漸層消失、不再說「右邊還有」', async () => {
    openJourney()
    await ready()
    expect(fadeVisible(), '放不下時右緣要有漸層').toBe(true)
    const max = Math.max(...readBoxes().map((b) => b.x + b.w)) + 16
    scrollGraphTo(Math.ceil(max * (readPercent()! / 100)) - GRAPH_W)
    await waitFor(() => expect(fadeVisible(), '捲到底漸層要消失').toBe(false))
    expect(hintText()).not.toMatch(/右邊還有/)
  })

  it('按「看全圖」：整張進容器，提示改「已看到全部 N 個節點」，漸層消失；低於 70% 百分比轉警告色', async () => {
    openJourney()
    await ready()
    await userEvent.click(screen.getByRole('button', { name: '看全圖' }))
    await waitFor(() => expect(hintText()).toMatch(/已看到全部\s*10\s*個節點/))
    const z = readPercent()! / 100
    const bs = readBoxes()
    expect(Math.max(...bs.map((b) => b.x + b.w)) * z, '看全圖後整張在容器內').toBeLessThanOrEqual(GRAPH_W + 0.5)
    expect(fadeVisible()).toBe(false)
    expect(z, '前置條件：1850px 寬的圖看全圖會低於 70%').toBeLessThan(0.7)
    const el = percentEl()!
    expect(el.className + ' ' + el.style.color, '低於 70% 要有警告樣式（class 含 low／warn，或內嵌顏色）').toMatch(/low|warn|var\(--color-warning/)
  })

  it('整張放得下時直接顯示「已看到全部 N 個節點」、沒有漸層', async () => {
    openJourney({
      [`GET ${API}/versions/3`]: () => json(short()),
      [`GET ${STATS3}`]: () => json(shortStats()),
    })
    await ready(3)
    expect(hintText()).toMatch(/已看到全部\s*3\s*個節點/)
    expect(fadeVisible()).toBe(false)
  })
})

describe('軌跡圖：同一套縮放與提示', () => {
  it('有縮放列與提示，初始縮放 75%～100%、右緣不切卡片文字，有漸層', async () => {
    openTrace()
    await ready()
    const z = readPercent()! / 100
    expect(z).toBeGreaterThanOrEqual(0.75 - 0.005)
    expect(z).toBeLessThanOrEqual(1)
    expect(cutThroughText(readBoxes(), GRAPH_W / z, z)).toEqual([])
    expect(hintText()).toMatch(/右邊還有\s*\d+\s*個節點，左右滑動或按[「『]看全圖[」』]/)
    expect(fadeVisible()).toBe(true)
  })

  it('按「看全圖」後提示改「已看到全部 10 個節點」', async () => {
    openTrace()
    await ready()
    await userEvent.click(screen.getByRole('button', { name: '看全圖' }))
    await waitFor(() => expect(hintText()).toMatch(/已看到全部\s*10\s*個節點/))
  })
})
