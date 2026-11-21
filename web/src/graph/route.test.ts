import { describe, expect, it } from 'vitest'
import { CARD_H, CARD_W, outletY } from './card'
import { placeRemove, polyPath, routeEdge, routeKind } from './route'

const a = { x: 0, y: 0 }
const from = { x: CARD_W, y: CARD_H / 2 }

describe('routeKind', () => {
  it('目標整張卡在右／左／左右重疊', () => {
    expect(routeKind(a, { x: 300, y: 0 })).toBe('forward')
    expect(routeKind({ x: 500, y: 0 }, { x: 100, y: 0 })).toBe('back')
    expect(routeKind(a, { x: 50, y: 300 })).toBe('column')
  })
})

describe('outletY', () => {
  it('單一出口在中點，多個出口等距且不同', () => {
    expect(outletY(0, 1)).toBe(CARD_H / 2)
    expect(outletY(0, 2)).toBeLessThan(outletY(1, 2))
    expect(outletY(0, 3)).toBe(30)
  })
})

describe('routeEdge', () => {
  it('forward：曲線，接目標左緣中點', () => {
    const r = routeEdge(from, a, { x: 300, y: 100 }, [a])
    expect(r.kind).toBe('forward')
    expect(r.d).toMatch(/^M200 60 C/)
    expect(r.end).toEqual({ x: 300, y: 160 })
    expect(r.side).toBe('up')
  })

  it('back：繞到所有卡片下方，只用 M L Q', () => {
    const src = { x: 600, y: 0 }
    const tgt = { x: 100, y: 0 }
    const mid = { x: 350, y: 200 }
    const r = routeEdge({ x: 800, y: 60 }, src, tgt, [src, tgt, mid])
    expect(r.kind).toBe('back')
    expect(r.end).toEqual({ x: 100, y: 60 })
    expect(r.d).not.toMatch(/[CSHVZacshvz]/)
    expect(r.mid.y).toBe(200 + CARD_H + 24)
    expect(r.side).toBe('down')
    expect(r.length).toBeGreaterThan(500)
  })

  it('同一欄往下：欄外走、接目標上緣中點', () => {
    const b = { x: 0, y: 300 }
    const r = routeEdge(from, a, b, [a, b])
    expect(r.kind).toBe('column')
    expect(r.end).toEqual({ x: 100, y: 300 })
    expect(Math.max(...r.points.map((p) => p.x))).toBe(CARD_W + 24)
    expect(r.side).toBe('right')
  })

  it('同一欄往上：接目標下緣中點；間距小於 48 時走在兩卡中間', () => {
    const up = routeEdge({ x: CARD_W, y: 360 }, { x: 0, y: 300 }, a, [a])
    expect(up.end).toEqual({ x: 100, y: CARD_H })
    const tight = routeEdge(from, a, { x: 0, y: CARD_H + 20 }, [a])
    expect(tight.points[2].y).toBe((CARD_H + CARD_H + 20) / 2)
    const tightUp = routeEdge({ x: CARD_W, y: CARD_H + 80 }, { x: 0, y: CARD_H + 20 }, a, [a])
    expect(tightUp.points[2].y).toBe((CARD_H + CARD_H + 20) / 2)
  })
})

describe('polyPath', () => {
  it('折角太短時退回直角', () => {
    expect(polyPath([{ x: 0, y: 0 }, { x: 0.2, y: 0 }, { x: 0.2, y: 50 }])).toBe('M0 0 L0.2 0 L0.2 50')
  })
})

describe('placeRemove', () => {
  const box = { left: 8, top: 8, right: 1000, bottom: 600 }
  const pill = { w: 120, h: 44 }
  it('長線膠囊在中點上方 28px；上方放不下換下方', () => {
    expect(placeRemove({ x: 500, y: 300 }, 'up', true, box, pill)).toEqual({ x: 500, y: 272 })
    expect(placeRemove({ x: 500, y: 30 }, 'up', true, box, pill).y).toBe(58)
  })
  it('欄外垂直線：放在右側，右邊放不下換左側；仍超出就夾進可見範圍', () => {
    expect(placeRemove({ x: 500, y: 300 }, 'right', true, box, pill).x).toBe(500 + 28 + 60)
    expect(placeRemove({ x: 990, y: 300 }, 'right', true, box, pill).x).toBe(990 - 88)
    expect(placeRemove({ x: 500, y: 300 }, 'down', true, box, pill)).toEqual({ x: 500, y: 328 })
    const clamped = placeRemove({ x: 2, y: 2 }, 'up', true, { left: 8, top: 8, right: 100, bottom: 100 }, pill)
    expect(clamped.x).toBeGreaterThanOrEqual(8 + 60)
  })
  it('短線圓鈕在中點', () => {
    expect(placeRemove({ x: 500, y: 300 }, 'up', false, box, { w: 44, h: 44 })).toEqual({ x: 500, y: 300 })
  })
})
