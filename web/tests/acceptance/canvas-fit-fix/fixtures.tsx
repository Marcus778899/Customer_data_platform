/**
 * canvas-fit-fix 共用：讓 jsdom 量得到畫布容器尺寸，才能驗證「符合視窗大小」的實際結果（uat 第三輪 F-19～F-21）。
 * 做法：把 `.react-flow` 容器的 offsetWidth／offsetHeight 設成固定大小、ResizeObserver 在 observe 時回報該大小；
 * 節點位置讀 `.react-flow__node` 的 translate、縮放與平移讀 `.react-flow__viewport` 的 transform，換算成容器內的螢幕座標。
 * 不測 fitView 內部，只測結果：每個節點整張卡都落在容器內、且不被小地圖／縮放列蓋住。
 */
export const BOX = { w: 1000, h: 600 }

let saved: { w?: PropertyDescriptor; h?: PropertyDescriptor; ro?: unknown } = {}

export function mockLayout() {
  const proto = HTMLElement.prototype
  saved = {
    w: Object.getOwnPropertyDescriptor(proto, 'offsetWidth'),
    h: Object.getOwnPropertyDescriptor(proto, 'offsetHeight'),
    ro: (globalThis as { ResizeObserver?: unknown }).ResizeObserver,
  }
  Object.defineProperty(proto, 'offsetWidth', {
    configurable: true,
    get(this: HTMLElement) {
      return this.classList.contains('react-flow') ? BOX.w : 100
    },
  })
  Object.defineProperty(proto, 'offsetHeight', {
    configurable: true,
    get(this: HTMLElement) {
      return this.classList.contains('react-flow') ? BOX.h : 50
    },
  })
  ;(globalThis as { ResizeObserver?: unknown }).ResizeObserver = class {
    cb: (e: unknown[]) => void
    constructor(cb: (e: unknown[]) => void) {
      this.cb = cb
    }
    observe(t: Element) {
      this.cb([{ target: t, contentRect: { width: BOX.w, height: BOX.h } }])
    }
    unobserve() {}
    disconnect() {}
  }
}

export function restoreLayout() {
  const proto = HTMLElement.prototype as unknown as Record<string, unknown>
  if (saved.w) Object.defineProperty(proto, 'offsetWidth', saved.w)
  else delete proto.offsetWidth
  if (saved.h) Object.defineProperty(proto, 'offsetHeight', saved.h)
  else delete proto.offsetHeight
  ;(globalThis as { ResizeObserver?: unknown }).ResizeObserver = saved.ro
}

export interface Rect {
  name: string
  x: number
  y: number
  w: number
  h: number
}

export function viewport() {
  const el = document.querySelector<HTMLElement>('.react-flow__viewport')
  const t = el?.style.transform ?? ''
  const m = /translate\(\s*(-?[\d.]+)px,\s*(-?[\d.]+)px\)\s*scale\(\s*([\d.]+)/.exec(t)
  if (!m) throw new Error(`viewport 沒有 transform：${t}`)
  return { x: Number(m[1]), y: Number(m[2]), zoom: Number(m[3]) }
}

/** 每個節點卡在容器內的螢幕矩形 */
export function nodeRects(): Rect[] {
  const vp = viewport()
  return [...document.querySelectorAll<HTMLElement>('.react-flow__node')].map((el) => {
    const m = /translate\(\s*(-?[\d.]+)px,\s*(-?[\d.]+)px\)/.exec(el.style.transform)
    if (!m) throw new Error(`節點沒有 transform：${el.style.transform}`)
    const w = Number.parseFloat(el.style.width) || 176
    const h = Number.parseFloat(el.style.height) || 88
    return {
      name: (el.textContent ?? '').slice(0, 20),
      x: vp.x + Number(m[1]) * vp.zoom,
      y: vp.y + Number(m[2]) * vp.zoom,
      w: w * vp.zoom,
      h: h * vp.zoom,
    }
  })
}

export function outsideBox(rs: Rect[]): string[] {
  return rs
    .filter((r) => r.x < -0.5 || r.y < -0.5 || r.x + r.w > BOX.w + 0.5 || r.y + r.h > BOX.h + 0.5)
    .map((r) => `「${r.name}」(${Math.round(r.x)},${Math.round(r.y)} ${Math.round(r.w)}x${Math.round(r.h)})`)
}

export const intersects = (
  a: { x: number; y: number; w: number; h: number },
  b: { x: number; y: number; w: number; h: number },
) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
