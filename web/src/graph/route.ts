// 編輯畫布的連線路徑（docs/ui/canvas-interaction.md §1「路徑」）：只用絕對座標指令 M L C Q。
// 目標在來源右邊：曲線；目標在左邊（不同欄）：從來源右側出、在所有卡片下方繞過去、接目標左緣中點；
// 同一欄：從來源右側出、在欄外上下走、接目標上緣（目標在下）或下緣（目標在上）中點。
import { CARD_H, CARD_W, ROUTE_OUT } from './card'

export interface Pt {
  x: number
  y: number
}
export type RouteKind = 'forward' | 'back' | 'column'
/** 移除鈕相對路線中點的偏好方向 */
export type Side = 'up' | 'down' | 'right'

export interface Route {
  d: string
  kind: RouteKind
  /** 路徑頂點（曲線以起訖點代表），算長度與找中點用 */
  points: Pt[]
  end: Pt
  /** 標籤與移除鈕的錨點 */
  mid: Pt
  side: Side
  /** 路徑長度（流程座標） */
  length: number
}

const RADIUS = 8
const SMALL_GAP = 48

const dist = (a: Pt, b: Pt) => Math.hypot(a.x - b.x, a.y - b.y)
const lengthOf = (pts: Pt[]) => pts.slice(1).reduce((n, p, i) => n + dist(pts[i], p), 0)
const fmt = (n: number) => String(Math.round(n * 100) / 100)
const at = (p: Pt) => `${fmt(p.x)} ${fmt(p.y)}`

/** 折線加圓角：只有 M、L、Q 指令，Q 的控制點就是折角 */
export function polyPath(pts: Pt[], radius = RADIUS): string {
  let d = `M${at(pts[0])}`
  for (let i = 1; i < pts.length - 1; i++) {
    const prev = pts[i - 1]
    const cur = pts[i]
    const next = pts[i + 1]
    const r = Math.min(radius, dist(prev, cur) / 2, dist(cur, next) / 2)
    if (r < 0.5) {
      d += ` L${at(cur)}`
      continue
    }
    const a = { x: cur.x + ((prev.x - cur.x) / dist(prev, cur)) * r, y: cur.y + ((prev.y - cur.y) / dist(prev, cur)) * r }
    const b = { x: cur.x + ((next.x - cur.x) / dist(next, cur)) * r, y: cur.y + ((next.y - cur.y) / dist(next, cur)) * r }
    d += ` L${at(a)} Q${at(cur)} ${at(b)}`
  }
  return `${d} L${at(pts[pts.length - 1])}`
}

/** 連線分類：目標整張卡在來源右邊＝forward；整張卡在左邊＝back；左右有重疊＝同一欄 */
export function routeKind(a: Pt, b: Pt): RouteKind {
  if (b.x >= a.x + CARD_W) return 'forward'
  if (b.x <= a.x - CARD_W) return 'back'
  return 'column'
}

/**
 * @param from 來源出口的流程座標（來源卡右緣）
 * @param a 來源卡左上角；@param b 目標卡左上角
 * @param cards 所有卡的左上角（繞行時避開）
 */
export function routeEdge(from: Pt, a: Pt, b: Pt, cards: readonly Pt[]): Route {
  const kind = routeKind(a, b)
  if (kind === 'forward') {
    const end = { x: b.x, y: b.y + CARD_H / 2 }
    const c = Math.max(32, (end.x - from.x) / 2)
    return {
      d: `M${at(from)} C${fmt(from.x + c)} ${fmt(from.y)} ${fmt(end.x - c)} ${fmt(end.y)} ${at(end)}`,
      kind,
      points: [from, end],
      end,
      mid: { x: (from.x + end.x) / 2, y: (from.y + end.y) / 2 },
      side: 'up',
      length: dist(from, end),
    }
  }
  if (kind === 'back') {
    const end = { x: b.x, y: b.y + CARD_H / 2 }
    const x1 = from.x + ROUTE_OUT
    const x2 = end.x - ROUTE_OUT
    const inSpan = cards.filter((p) => p.x + CARD_W > x2 && p.x < x1)
    const yc = Math.max(a.y, b.y, ...inSpan.map((p) => p.y)) + CARD_H + ROUTE_OUT
    const points = [from, { x: x1, y: from.y }, { x: x1, y: yc }, { x: x2, y: yc }, { x: x2, y: end.y }, end]
    return { d: polyPath(points), kind, points, end, mid: { x: (x1 + x2) / 2, y: yc }, side: 'down', length: lengthOf(points) }
  }
  const down = b.y > a.y
  const xr = Math.max(a.x, b.x) + CARD_W + ROUTE_OUT
  const end = { x: b.x + CARD_W / 2, y: down ? b.y : b.y + CARD_H }
  const gap = down ? b.y - (a.y + CARD_H) : a.y - (b.y + CARD_H)
  const near = down ? b.y - ROUTE_OUT : b.y + CARD_H + ROUTE_OUT
  const yy = gap >= SMALL_GAP || gap < 0 ? near : down ? (a.y + CARD_H + b.y) / 2 : (b.y + CARD_H + a.y) / 2
  const points = [from, { x: xr, y: from.y }, { x: xr, y: yy }, { x: end.x, y: yy }, end]
  return { d: polyPath(points), kind, points, end, mid: { x: xr, y: (from.y + yy) / 2 }, side: 'right', length: lengthOf(points) }
}

export interface Rect {
  left: number
  top: number
  right: number
  bottom: number
}

/**
 * 移除鈕在畫面（像素）的位置：長線（畫面上 >= 160px）用膠囊鈕放在中點上方 28px，短線用 44px 圓鈕放在中點；
 * 偏好位置超出可見範圍就換另一側，最後仍夾在可見範圍內（視覺評審問題 1）。
 * @param mid 路線中點的畫面座標；@param box 可見範圍；@param size 按鈕寬高
 */
export function placeRemove(mid: Pt, side: Side, long: boolean, box: Rect, size: { w: number; h: number }): Pt {
  const gap = 28
  const sideX = gap + size.w / 2
  const cands: Pt[] = long
    ? side === 'right'
      ? [{ x: mid.x + sideX, y: mid.y }, { x: mid.x - sideX, y: mid.y }]
      : side === 'down'
        ? [{ x: mid.x, y: mid.y + gap }, { x: mid.x, y: mid.y - gap }]
        : [{ x: mid.x, y: mid.y - gap }, { x: mid.x, y: mid.y + gap }]
    : [mid]
  const inside = (p: Pt) => p.x - size.w / 2 >= box.left && p.x + size.w / 2 <= box.right && p.y - size.h / 2 >= box.top && p.y + size.h / 2 <= box.bottom
  const hit = cands.find(inside) ?? cands[0]
  return {
    x: Math.min(Math.max(hit.x, box.left + size.w / 2), Math.max(box.left + size.w / 2, box.right - size.w / 2)),
    y: Math.min(Math.max(hit.y, box.top + size.h / 2), Math.max(box.top + size.h / 2, box.bottom - size.h / 2)),
  }
}
