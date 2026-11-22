// @vitest-environment jsdom
/**
 * canvas-add-reveal-fix F-54（uat 第九輪）：面板開著時從節點庫連續新增節點，新節點落在畫布外沒被帶進畫面；
 * 「已移動畫面」提示與實際不符。依據 docs/ui/canvas-interaction.md §1。
 * 做法沿用 canvas-uat-fix/panel-resize：jsdom 不排版，用容器尺寸 mock。畫布寬照 uat 實測：1440 約 722、1280 約 562（面板已讓出寬度）。
 * 每次新增：新節點卡（含出口圓點與 48px 漸層）都要在畫布可見範圍內；提示「已移動畫面」只在視角（平移或縮放）真的變了才可出現。
 * 不自行點新節點（點選本身會觸發既有的選取露出，會蓋掉問題）；新增後的選取由編輯器自己做。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, editorBackend, library, libraryItem, resetEnv, screen, skeleton, teardown, userEvent, waitFor } from '../journey-canvas-ui/fixtures'
import { openBare } from '../canvas-connect-drag/helpers'
import { BOX, mockLayout, nodeRects, restoreLayout, viewport } from '../canvas-fit-fix/fixtures'

type Cb = (e: unknown[]) => void
const observers: Array<{ cb: Cb; target: Element }> = []
/** 把畫布寬改成 w 並通知所有 ResizeObserver（等同面板開關後瀏覽器重排） */
function resizeCanvas(w: number) {
  BOX.w = w
  for (const o of observers) o.cb([{ target: o.target, contentRect: { width: BOX.w, height: BOX.h } }])
}

const saved: { iw?: PropertyDescriptor; mm?: unknown; ow?: PropertyDescriptor; cw?: PropertyDescriptor; rect?: unknown } = {}
let areaW = 0

function setViewport(vw: number, aw: number) {
  areaW = aw
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: vw })
  ;(window as unknown as { matchMedia: unknown }).matchMedia = (q: string) => {
    const max = /max-width:\s*(\d+)px/.exec(q)
    const min = /min-width:\s*(\d+)px/.exec(q)
    const matches = (!max || window.innerWidth <= Number(max[1])) && (!min || window.innerWidth >= Number(min[1])) && (!!max || !!min)
    return { matches, media: q, onchange: null, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false }
  }
}

beforeEach(() => {
  resetEnv()
  mockLayout()
  observers.length = 0
  ;(globalThis as { ResizeObserver?: unknown }).ResizeObserver = class {
    cb: Cb
    constructor(cb: Cb) { this.cb = cb }
    observe(t: Element) {
      observers.push({ cb: this.cb, target: t })
      this.cb([{ target: t, contentRect: { width: BOX.w, height: BOX.h } }])
    }
    unobserve() {}
    disconnect() {
      for (let i = observers.length - 1; i >= 0; i--) if (observers[i].cb === this.cb) observers.splice(i, 1)
    }
  }
  const proto = HTMLElement.prototype
  saved.iw = Object.getOwnPropertyDescriptor(window, 'innerWidth')
  saved.mm = (window as unknown as { matchMedia?: unknown }).matchMedia
  saved.ow = Object.getOwnPropertyDescriptor(proto, 'offsetWidth')
  saved.cw = Object.getOwnPropertyDescriptor(proto, 'clientWidth')
  saved.rect = proto.getBoundingClientRect
  const w = (el: HTMLElement) => (el.classList.contains('react-flow') ? BOX.w : el.classList.contains('canvas-area') ? areaW : 100)
  Object.defineProperty(proto, 'offsetWidth', { configurable: true, get(this: HTMLElement) { return w(this) } })
  Object.defineProperty(proto, 'clientWidth', { configurable: true, get(this: HTMLElement) { return w(this) } })
  proto.getBoundingClientRect = function (this: HTMLElement) {
    const x = w(this)
    return { x: 0, y: 0, left: 0, top: 0, right: x, bottom: 50, width: x, height: 50, toJSON() {} } as DOMRect
  }
})

afterEach(() => {
  cleanup()
  teardown()
  const proto = HTMLElement.prototype
  if (saved.iw) Object.defineProperty(window, 'innerWidth', saved.iw)
  ;(window as unknown as { matchMedia?: unknown }).matchMedia = saved.mm
  if (saved.cw) Object.defineProperty(proto, 'clientWidth', saved.cw)
  else delete (proto as unknown as Record<string, unknown>).clientWidth
  proto.getBoundingClientRect = saved.rect as typeof proto.getBoundingClientRect
  Object.defineProperty(proto, 'offsetWidth', saved.ow ?? { configurable: true, value: undefined })
  restoreLayout()
  BOX.w = 1000
})

const settled = (ms = 400) => new Promise((r) => setTimeout(r, ms))
const vpKey = () => {
  const v = viewport()
  return { x: v.x, y: v.y, zoom: v.zoom }
}
const sameVp = (a: ReturnType<typeof vpKey>, b: ReturnType<typeof vpKey>) =>
  Math.abs(a.x - b.x) < 0.5 && Math.abs(a.y - b.y) < 0.5 && Math.abs(a.zoom - b.zoom) < 0.001

const ADDS = ['等待', '條件', '訊息', '合流', '結束']

async function run(vw: number, canvasW: number) {
  setViewport(vw, vw - 56 - 48 - 228)
  BOX.w = canvasW + 300 // 面板關著時畫布較寬
  editorBackend({ def: skeleton(), revision: 1 })
  await openBare()
  await waitFor(() => expect(nodeRects().length).toBeGreaterThan(0))
  // 面板開著（uat 重現：先開旅程設定面板，再從節點庫新增）
  await userEvent.click(screen.getByRole('button', { name: '旅程設定' }))
  await screen.findByRole('complementary', { name: /旅程設定/ })
  resizeCanvas(canvasW) // 面板打開，畫布讓出寬度
  await settled()
  for (const label of ADDS) {
    const before = nodeRects().length
    const vpBefore = vpKey()
    await userEvent.click(libraryItem(library(), label))
    await waitFor(() => expect(nodeRects().length, `按「${label}」後沒有新節點`).toBe(before + 1))
    // 真實瀏覽器：新增後面板內容換成新節點、畫布容器再被量一次（寬度不變）；不可因此把視角重排回靠左
    resizeCanvas(canvasW)
    await settled()
    resizeCanvas(canvasW)
    await settled()
    const panels = screen.queryAllByRole('complementary').filter((e) => e.getAttribute('aria-label') !== '節點庫')
    expect(panels.length, `新增「${label}」後右側面板應仍開著`).toBeGreaterThan(0)
    const rects = nodeRects()
    const r = rects[rects.length - 1]
    const z = viewport().zoom
    const ctx = `新增「${label}」(${r.name})：卡 x=${Math.round(r.x)}..${Math.round(r.x + r.w)}，畫布寬 ${BOX.w}，視角 ${JSON.stringify(vpKey())}`
    expect(r.x, ctx).toBeGreaterThanOrEqual(-0.5)
    expect(r.x + r.w + 8 * z + 48, `${ctx}（出口圓點＋漸層要在畫布內）`).toBeLessThanOrEqual(BOX.w + 0.5)
    if (screen.queryByText(/已移動畫面/)) expect(sameVp(vpBefore, vpKey()), `${ctx}：出現「已移動畫面」但視角沒變`).toBe(false)
  }
}

describe('F-54：面板開著連續新增節點，都要被帶進可見範圍', () => {
  it('1440（畫布約 722px）：等待、條件、訊息、合流、結束', async () => {
    await run(1440, 722)
  }, 120000)

  it('1280（畫布約 562px）：等待、條件、訊息、合流、結束', async () => {
    await run(1280, 562)
  }, 120000)
})
