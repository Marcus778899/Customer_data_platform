import { describe, expect, it } from 'vitest'
import { CARD_H, fillLayout, isAutoLayout, layoutAll, topologyKey, type Def } from './model'
import { fitScale } from '../graph/FlowGraph'

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

describe('旅程頁縮放', () => {
  it('fitScale：放得下不縮、放不下等比縮、最小 0.6、量不到就不縮', () => {
    expect(fitScale(1000, 1160)).toBe(1)
    expect(fitScale(1440, 1160)).toBeCloseTo(0.806, 2)
    expect(fitScale(3000, 1160)).toBe(0.6)
    expect(fitScale(1440, 0)).toBe(1)
  })
})
