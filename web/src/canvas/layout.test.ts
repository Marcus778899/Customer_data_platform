import { describe, expect, it } from 'vitest'
import { CARD_H, fillLayout, isAutoLayout, layoutAll, topologyKey, type Def } from './model'
import { pickZoom } from '../graph/card'

const def = (): Def =>
  ({
    entry_node: 'start',
    settings: {},
    nodes: [
      { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'c' } },
      { id: 'c', type: 'ConditionNode', name: null, config: {}, outputs: { yes: 'a', no: 'b' } },
      { id: 'a', type: 'EndNode', name: null, config: {}, outputs: {} },
      { id: 'b', type: 'EndNode', name: null, config: {}, outputs: {} },
    ],
  }) as unknown as Def

describe('畫布排版', () => {
  it('layoutAll 由左到右分層，是／否分上下兩列', () => {
    const l = layoutAll(def()).nodes
    expect(l.c.x).toBeGreaterThan(l.start.x)
    expect(l.a.x).toBe(l.b.x)
    expect(Math.abs(l.a.y - l.b.y)).toBeGreaterThanOrEqual(CARD_H)
  })
  it('isAutoLayout：自動排版為真，拖過為假；fillLayout 保留已有座標', () => {
    const d = def()
    const auto = layoutAll(d)
    expect(isAutoLayout(d, auto)).toBe(true)
    const moved = { nodes: { ...auto.nodes, a: { x: 5, y: 5 } } }
    expect(isAutoLayout(d, moved)).toBe(false)
    expect(fillLayout(d, moved).nodes.a).toEqual({ x: 5, y: 5 })
    expect(isAutoLayout(d, { nodes: {} })).toBe(false)
  })
  it('topologyKey 只跟節點與連線有關', () => {
    const d = def()
    const renamed = { ...d, nodes: d.nodes.map((n) => ({ ...n, name: 'x' })) } as Def
    expect(topologyKey(renamed)).toBe(topologyKey(d))
    const rewired = { ...d, nodes: d.nodes.map((n) => (n.id === 'c' ? { ...n, outputs: { yes: 'a', no: 'a' } } : n)) } as Def
    expect(topologyKey(rewired)).not.toBe(topologyKey(d))
  })
})

describe('初始縮放 pickZoom', () => {
  const xs = [0, 272, 544, 816, 1088]
  it('放得下 100%；縮到 75% 以上放得下就縮到剛好放得下', () => {
    expect(pickZoom(1160, 1000, () => 0, xs)).toBe(1)
    expect(pickZoom(1160, 1400, () => 0, xs)).toBe(0.82)
  })
  it('放不下：取 75%～100% 中右緣不切在卡片文字中間的最大值', () => {
    const z = pickZoom(1160, 2400, (v) => 1160 / v, xs)
    expect(z).toBeGreaterThanOrEqual(0.75)
    const r = 1160 / z
    expect(xs.some((x) => r > x + 12 && r < x + 200)).toBe(false)
  })
  it('75%～100% 都切在文字中間：手機放大到 125%，仍找不到就 75%', () => {
    expect(pickZoom(295, 1300, (v) => 319 / v, [0, 272, 544])).toBeGreaterThan(1)
    expect(pickZoom(295, 1300, () => 100, [0])).toBe(0.75)
  })
  it('量不到寬度：不縮', () => {
    expect(pickZoom(0, 1440, () => 0, xs)).toBe(1)
  })
})
