/**
 * node-card-unify 共用：讓 jsdom 量得到唯讀圖容器寬度，並從 DOM 讀出節點卡位置、縮放百分比、提示文字。
 *
 * 對 worker 的假設（規格 docs/ui/canvas-interaction.md §2 沒寫死的，全部列在這裡）：
 *   - 唯讀圖（旅程頁、軌跡頁）的捲動容器是既有的 `.graph`（role=group、有 aria-label）。測試把它的 clientWidth 固定為 GRAPH_W，
 *     scrollWidth 依「節點右緣最大值＋16 邊距 × 目前縮放」動態計算，scrollLeft 可寫入。
 *   - 唯讀圖有縮放列：role=group、名稱「縮放」，內有按鈕「縮小」「放大」「看全圖」與百分比文字（`NN%`，與編輯畫布同寫法）。
 *   - 提示是 role=status 的文字，與縮放列在同一個父元素內、不在 `.graph` 裡（不浮在圖上）。
 *   - 目前縮放 = 百分比文字 / 100；節點卡位置讀 `.react-flow__node` 的 translate（流程座標）與 style 寬高。
 *     捲動位置 0 時，容器右緣在流程座標 = GRAPH_W / 縮放。
 *   - 卡片尺寸常數（寬 200、高 120／144 等）放在 app.css（或 `.node` 直接寫死），不放 tokens.css（不在負責檔案，規格無新 token）；
 *     css-rules.test.ts 的 var() 只查 app.css 自己的定義再查 tokens.css。
 *   - 連線接點座標（canvas-connect-drag 改）：唯讀圖維持右緣中點→左緣中點；編輯畫布多出口各出口在右緣不同高度、同欄回頭線接目標上／下緣中點。
 *     詳見 anchorMismatches 的註解（mode 'readonly'／'editor'）。
 *   - 漸層元素 class 含 `fade`（編輯畫布 `.cvfade`）；「消失」＝元素不存在、有 hidden、或自己／祖先帶 class `end`、opacity 0、display none。
 */
import { screen, waitFor } from '@testing-library/react'
import { expect } from 'vitest'

export const GRAPH_W = 1160
export const CARD_W = 200

export interface Box {
  name: string
  x: number
  y: number
  w: number
  h: number
}

let saved: { cw?: PropertyDescriptor; sw?: PropertyDescriptor; ro?: unknown } = {}
const scrollPos = new WeakMap<Element, number>()

export function mockGraphWidth(width: number = GRAPH_W) {
  const proto = HTMLElement.prototype
  saved = {
    cw: Object.getOwnPropertyDescriptor(proto, 'clientWidth'),
    sw: Object.getOwnPropertyDescriptor(proto, 'scrollWidth'),
    ro: (globalThis as { ResizeObserver?: unknown }).ResizeObserver,
  }
  Object.defineProperty(proto, 'clientWidth', {
    configurable: true,
    get(this: HTMLElement) {
      return this.classList.contains('graph') ? width : 0
    },
  })
  Object.defineProperty(proto, 'scrollWidth', {
    configurable: true,
    get(this: HTMLElement) {
      if (!this.classList.contains('graph')) return 0
      const right = Math.max(0, ...readBoxes().map((b) => b.x + b.w)) + 16
      return Math.max(width, Math.ceil(right * (readPercent() ?? 100) / 100))
    },
  })
  ;(globalThis as { ResizeObserver?: unknown }).ResizeObserver = class {
    cb: (e: unknown[]) => void
    constructor(cb: (e: unknown[]) => void) {
      this.cb = cb
    }
    observe(t: Element) {
      this.cb([{ target: t, contentRect: { width, height: 600 } }])
    }
    unobserve() {}
    disconnect() {}
  }
}

export function restoreGraphWidth() {
  const proto = HTMLElement.prototype as unknown as Record<string, unknown>
  if (saved.cw) Object.defineProperty(proto, 'clientWidth', saved.cw)
  else delete proto.clientWidth
  if (saved.sw) Object.defineProperty(proto, 'scrollWidth', saved.sw)
  else delete proto.scrollWidth
  ;(globalThis as { ResizeObserver?: unknown }).ResizeObserver = saved.ro
}

export const graphEl = () => document.querySelector<HTMLElement>('.graph')

/** 把 `.graph` 捲到指定位置並送出 scroll 事件 */
export function scrollGraphTo(x: number) {
  const el = graphEl()!
  scrollPos.set(el, x)
  Object.defineProperty(el, 'scrollLeft', { configurable: true, get: () => scrollPos.get(el) ?? 0, set: (v: number) => scrollPos.set(el, v) })
  el.dispatchEvent(new Event('scroll', { bubbles: true }))
}

/** 每個節點卡的流程座標與尺寸 */
export function readBoxes(): Box[] {
  return [...document.querySelectorAll<HTMLElement>('.react-flow__node')].map((el) => {
    const m = /translate\(\s*(-?[\d.]+)px,\s*(-?[\d.]+)px\)/.exec(el.style.transform)
    return {
      name: (el.textContent ?? '').replace(/\s+/g, ' ').slice(0, 24),
      x: m ? Number(m[1]) : Number.NaN,
      y: m ? Number(m[2]) : Number.NaN,
      w: Number.parseFloat(el.style.width),
      h: Number.parseFloat(el.style.height),
    }
  })
}

export const zoomGroup = () => screen.queryByRole('group', { name: '縮放' })

export function readPercent(): number | null {
  const g = zoomGroup()
  const m = /(\d+)\s*%/.exec(g?.textContent ?? '')
  return m ? Number(m[1]) : null
}

export const waitForPercent = async () => waitFor(() => expect(readPercent(), '找不到縮放列的百分比文字').not.toBeNull())

/** 縮放列的百分比元素（文字剛好是 NN%） */
export function percentEl(): HTMLElement | null {
  const g = zoomGroup()
  return [...(g?.querySelectorAll<HTMLElement>('*') ?? [])].find((e) => e.children.length === 0 && /^\s*\d+\s*%\s*$/.test(e.textContent ?? '')) ?? null
}

/** 提示文字（縮放列同一個父元素內、role=status、含「節點」） */
export function hintEl(): HTMLElement | null {
  const g = zoomGroup()
  const parent = g?.parentElement
  return [...(parent?.querySelectorAll<HTMLElement>('[role="status"]') ?? [])].find((e) => /節點/.test(e.textContent ?? '')) ?? null
}

export const hintText = () => (hintEl()?.textContent ?? '').replace(/\s+/g, ' ').trim()

export function fadeVisible(root: ParentNode = document): boolean {
  const els = [...root.querySelectorAll<HTMLElement>('[class*="fade"]')]
  return els.some((el) => {
    if (el.hidden || el.closest('[hidden]') || el.closest('.end')) return false
    const s = el.style
    return s.display !== 'none' && s.opacity !== '0'
  })
}

/** 邊的路徑起點與終點（流程座標） */
export function edgeEnds(): Array<{ id: string; start: [number, number]; end: [number, number] }> {
  return [...document.querySelectorAll<SVGPathElement>('[data-testid^="rf__edge-"] path.react-flow__edge-path')].map((p) => {
    const nums = (p.getAttribute('d') ?? '').match(/-?\d+(?:\.\d+)?/g)?.map(Number) ?? []
    const wrap = p.closest('[data-testid^="rf__edge-"]')!
    return {
      id: wrap.getAttribute('data-testid')!,
      start: [nums[0], nums[1]] as [number, number],
      end: [nums[nums.length - 2], nums[nums.length - 1]] as [number, number],
    }
  })
}

/**
 * 連線接點檢查。接點座標的假設（canvas-connect-drag 之後；規格 docs/ui/canvas-interaction.md §1，路徑以 `path` 的 d 屬性第一組與最後一組座標為準）：
 *   - 'readonly'（預設；旅程頁、軌跡圖，沿用 node-card-unify）：起點＝某張卡右緣中點、終點＝某張卡左緣中點（誤差 1.5）。
 *   - 'editor'（編輯畫布）：
 *       起點＝某張卡右緣（x ＝ 卡右緣）、y 在卡的上下緣之間；單一出口取中點，多出口（條件 是／否、A/B）各出口 y 不同，
 *       所以不要求中點；
 *       終點＝某張卡的入口：左緣中點（預設）、上緣中點（同一欄且目標在來源下方）、下緣中點（同一欄且目標在來源上方）。
 *       誤差 3（箭頭可以停在入口前 2px）。
 *     同欄回頭線與多出口的幾何另在 canvas-connect-drag/edges.test.tsx 檢查。
 */
export function anchorMismatches(boxes: Box[] = readBoxes(), mode: 'readonly' | 'editor' = 'readonly'): string[] {
  const tol = mode === 'editor' ? 3 : 1.5
  const near = (a: number, b: number) => Math.abs(a - b) <= tol
  const out: string[] = []
  for (const e of edgeEnds()) {
    const s = boxes.some((b) =>
      near(e.start[0], b.x + b.w) && (mode === 'editor' ? e.start[1] >= b.y - tol && e.start[1] <= b.y + b.h + tol : near(e.start[1], b.y + b.h / 2)),
    )
    const t = boxes.some(
      (b) =>
        (near(e.end[0], b.x) && near(e.end[1], b.y + b.h / 2)) ||
        (mode === 'editor' && near(e.end[0], b.x + b.w / 2) && (near(e.end[1], b.y) || near(e.end[1], b.y + b.h))),
    )
    if (!s) out.push(`${e.id} 起點 (${e.start}) 不在任何卡的右緣${mode === 'editor' ? '' : '中點'}`)
    if (!t) out.push(`${e.id} 終點 (${e.end}) 不在任何卡的入口（左緣中點${mode === 'editor' ? '、上緣中點、下緣中點' : ''}）`)
  }
  return out
}

/** 螢幕上右緣（寬 w、縮放 z）切在卡片文字中間的卡：色條與邊框之後（距左緣 17px）到卡片右緣之前 */
export function cutThroughText(boxes: Box[], rightEdge: number, scale: number): string[] {
  return boxes
    .filter((b) => rightEdge > b.x + 17 / scale && rightEdge < b.x + b.w - 0.5)
    .map((b) => `「${b.name}」x=${b.x} 右緣在 ${Math.round(rightEdge)}`)
}
