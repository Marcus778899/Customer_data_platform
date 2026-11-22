// @vitest-environment jsdom
/**
 * canvas-uat-fix（architect review 2 項）：面板改成排在畫布旁邊（flex）後，開關面板會改變畫布寬。
 * 1. 開關面板造成的寬度變化不可讓自動版面（place）蓋掉選取節點的平移／使用者的視角；選取的最右欄節點最後要在可見範圍內。
 * 2. 判斷「手機／面板在下方」不可只看畫布（.react-flow）寬：桌機 1280 視窗開面板時畫布約 562px，縮放列仍要在。
 * jsdom 不算版面：用 canvas-fit-fix 的容器尺寸 mock，並讓 ResizeObserver 可手動再觸發（模擬面板開關後的重排）。
 * 視窗寬度同時以 window.innerWidth 與 matchMedia（依 innerWidth 評估 max-width／min-width）提供，實作用哪一種都行；
 * `.canvas-area` 的寬度以 offsetWidth／clientWidth／getBoundingClientRect 提供（視窗寬 - 側欄與邊距 - 節點庫 - 面板之外的部分）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, editorBackend, resetEnv, screen, selectNode, smallDef, teardown, userEvent, waitFor, within } from '../journey-canvas-ui/fixtures'
import type { Def } from '../journey-canvas-ui/fixtures'
import { openBare } from '../canvas-connect-drag/helpers'
import { BOX, mockLayout, nodeRects, restoreLayout, viewport } from '../canvas-fit-fix/fixtures'

/** 最右欄是有出口的節點（等待）；長鏈使整張圖比面板打開後的畫布寬 */
const longDef = (): Def => {
  const d = smallDef()
  return {
    ...d,
    nodes: d.nodes.map((n) => (n.id === 'end_1' ? { ...n, type: 'WaitDelayNode', name: '最後等待', config: { duration: 'PT1H' }, outputs: {} } : n)),
  }
}

type Cb = (e: unknown[]) => void
const observers: Array<{ cb: Cb; target: Element }> = []
/** 把畫布寬改成 w 並通知所有 ResizeObserver（等同面板開關後瀏覽器重排） */
function resizeCanvas(w: number) {
  BOX.w = w
  for (const o of observers) o.cb([{ target: o.target, contentRect: { width: BOX.w, height: BOX.h } }])
}

let areaW = 0
const saved: { iw?: PropertyDescriptor; mm?: unknown; ow?: PropertyDescriptor; cw?: PropertyDescriptor; rect?: unknown } = {}

/** 視窗寬 vw 與 .canvas-area 寬 aw */
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
  // mockLayout 的 ResizeObserver 換成可手動再觸發的版本（restoreLayout 會還原）
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
  Object.defineProperty(proto, 'offsetWidth', {
    configurable: true,
    get(this: HTMLElement) {
      return this.classList.contains('react-flow') ? BOX.w : this.classList.contains('canvas-area') ? areaW : 100
    },
  })
  Object.defineProperty(proto, 'clientWidth', {
    configurable: true,
    get(this: HTMLElement) {
      return this.classList.contains('react-flow') ? BOX.w : this.classList.contains('canvas-area') ? areaW : 100
    },
  })
  proto.getBoundingClientRect = function (this: HTMLElement) {
    const w = this.classList.contains('react-flow') ? BOX.w : this.classList.contains('canvas-area') ? areaW : 100
    return { x: 0, y: 0, left: 0, top: 0, right: w, bottom: 50, width: w, height: 50, toJSON() {} } as DOMRect
  }
  BOX.w = 1000
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
const rightOf = (name: string) => {
  const r = nodeRects().find((x) => x.name.includes(name))!
  return { r, z: viewport().zoom }
}

describe('開關面板不可蓋掉選取節點的平移', () => {
  it('選取最右欄節點後面板打開、畫布變窄（1000 → 722）：最後該節點卡右緣＋出口圓點＋48px 漸層仍在畫布內', async () => {
    setViewport(1440, 1440 - 56 - 48 - 228)
    editorBackend({ def: longDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(nodeRects().length).toBe(6))
    await selectNode('最後等待')
    await screen.findByRole('complementary', { name: /最後等待/ })
    await settled()
    resizeCanvas(722)
    await settled()
    await waitFor(() => {
      const { r, z } = rightOf('最後等待')
      expect(r.x + r.w + 8 * z + 48, `卡右緣 ${Math.round(r.x + r.w)}，畫布 ${BOX.w}（被自動版面重排回靠左對齊？）`).toBeLessThanOrEqual(BOX.w + 0.5)
      expect(r.x).toBeGreaterThanOrEqual(-0.5)
    }, { timeout: 4000 })
  }, 60000)

  it('面板開著選取節點後只關面板（畫布變寬）：視角不被重設（平移與縮放不變）', async () => {
    setViewport(1440, 1440 - 56 - 48 - 228)
    BOX.w = 722
    editorBackend({ def: longDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(nodeRects().length).toBe(6))
    await selectNode('最後等待')
    const panel = await screen.findByRole('complementary', { name: /最後等待/ })
    await settled()
    const before = viewport()
    await userEvent.click(within(panel).getByRole('button', { name: /關閉/ }))
    await waitFor(() => expect(screen.queryByRole('complementary', { name: /最後等待/ })).toBeNull())
    resizeCanvas(1000)
    await settled()
    const after = viewport()
    expect(after.zoom).toBeCloseTo(before.zoom, 3)
    expect(after.x).toBeCloseTo(before.x, 0)
    expect(after.y).toBeCloseTo(before.y, 0)
  }, 60000)

  it('視窗真的改變大小（面板沒開關）仍要重新自動版面：視角重新計算（不退步）', async () => {
    setViewport(1440, 1440 - 56 - 48 - 228)
    BOX.w = 1400
    editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(nodeRects().length).toBe(6))
    await settled()
    const before = viewport()
    resizeCanvas(1000)
    await waitFor(() => {
      const after = viewport()
      expect(Math.abs(after.x - before.x) + Math.abs(after.zoom - before.zoom) * 1000, '畫布寬度真的變了，要重新自動版面').toBeGreaterThan(1)
    }, { timeout: 4000 })
  }, 60000)
})

describe('手機／面板在下方的判斷不可只看畫布寬', () => {
  it('1280 視窗開面板（畫布 562px，.canvas-area 約 948px）：縮放列（縮小、放大、看全圖）仍在', async () => {
    setViewport(1280, 1280 - 56 - 48 - 228)
    BOX.w = 562
    editorBackend({ def: longDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(nodeRects().length).toBe(6))
    await selectNode('最後等待')
    await screen.findByRole('complementary', { name: /最後等待/ })
    await settled()
    const bar = screen.getByRole('group', { name: '縮放' })
    expect(within(bar).getByRole('button', { name: '縮小' })).toBeTruthy()
    expect(within(bar).getByRole('button', { name: '放大' })).toBeTruthy()
    expect(within(bar).getByRole('button', { name: '看全圖' })).toBeTruthy()
  }, 60000)

  it('1280 視窗開面板：選取最右欄節點照桌機的右緣規則平移（卡右緣＋圓點＋48px 漸層都在 562px 內）', async () => {
    setViewport(1280, 1280 - 56 - 48 - 228)
    BOX.w = 562
    editorBackend({ def: longDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(nodeRects().length).toBe(6))
    await selectNode('最後等待')
    await screen.findByRole('complementary', { name: /最後等待/ })
    await waitFor(() => {
      const { r, z } = rightOf('最後等待')
      expect(r.x + r.w + 8 * z + 48, `卡右緣 ${Math.round(r.x + r.w)}，畫布 ${BOX.w}`).toBeLessThanOrEqual(BOX.w + 0.5)
      expect(r.x).toBeGreaterThanOrEqual(-0.5)
    }, { timeout: 4000 })
  }, 60000)

  it('真正的手機視窗（500px）：不顯示縮放列（不退步）', async () => {
    setViewport(500, 500 - 48)
    BOX.w = 452
    editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(nodeRects().length).toBe(6))
    await settled()
    expect(screen.queryByRole('group', { name: '縮放' })).toBeNull()
  }, 60000)
})
