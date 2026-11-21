import { describe, expect, it } from 'vitest'
import { MENU_GAP, MENU_H, MENU_W, placeLinkMenu, type MenuInput } from './menu'

const base = (over: Partial<MenuInput> = {}): MenuInput => ({
  press: { x: 0, y: 0 },
  pointer: { x: 400, y: 300 },
  box: { left: 100, top: 50, w: 1000, h: 700 },
  flow: { x: 10, y: 20 },
  from: { id: 'a', port: 'yes' },
  node: { x: 0, y: 0 },
  outputs: 2,
  index: 0,
  viewport: { x: 0, y: 0, zoom: 1 },
  ...over,
})

describe('placeLinkMenu', () => {
  it('只是點一下圓點（距離太短）不開選單', () => {
    expect(placeLinkMenu(base({ press: { x: 399, y: 299 } }))).toBeNull()
  })
  it('開在放開點右側 16px，並回傳來源出口與放開處', () => {
    const m = placeLinkMenu(base())!
    expect(m.drop).toEqual({ x: 300, y: 250 })
    expect(m.x).toBe(300 + MENU_GAP)
    expect(m.start).toEqual({ x: 200, y: 40 })
    expect(m.from).toEqual({ id: 'a', port: 'yes' })
  })
  it('右邊放不下改左側；沒有按下座標也能開', () => {
    const m = placeLinkMenu(base({ press: null, pointer: { x: 1050, y: 300 } }))!
    expect(m.x).toBe(950 - MENU_GAP - MENU_W)
  })
  it('y 夾在畫布內；容器大小未知時用放開點', () => {
    expect(placeLinkMenu(base({ pointer: { x: 400, y: 740 } }))!.y).toBe(700 - MENU_H - 8)
    expect(placeLinkMenu(base({ box: { left: 0, top: 0, w: 0, h: 0 } }))!.y).toBe(300)
  })
  it('選單會蓋住來源出口時往下移；找不到來源節點時以放開處當起點', () => {
    const covered = placeLinkMenu(base({ pointer: { x: 200 - MENU_GAP + 100, y: 100 }, box: { left: 100, top: 50, w: 1000, h: 700 }, node: { x: -60, y: 0 } }))!
    expect(covered.y).toBeGreaterThanOrEqual(8)
    const covered2 = placeLinkMenu(base({ pointer: { x: 190, y: 90 }, node: { x: -100, y: 0 }, index: 1 }))!
    expect(covered2.y).toBe(Math.min(covered2.start.y + 24, 700 - MENU_H - 8))
    expect(placeLinkMenu(base({ node: undefined }))!.start).toEqual({ x: 300, y: 250 })
  })
})
