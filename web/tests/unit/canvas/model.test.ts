import { describe, expect, it } from 'vitest'
import { friendlyTime } from '../../../src/canvas/Banners'
import {
  buildDuration, condValueText, durationText, fillLayout, findLoops, ID_RE, incomingOf, linkTargets, makeNode,
  newId, outputSpecs, parseCondValue, parseDuration, placeNew, removeNode, setOutput, summaryOf, textOutline,
  type Def,
} from '../../../src/canvas/model'

const base = (): Def => ({
  name: 't', description: null, settings: {}, entry_node: 'start',
  nodes: [
    { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'a' } },
    { id: 'a', type: 'WaitDelayNode', name: '等', config: { duration: 'PT1H' }, outputs: { next: 'b' } },
    { id: 'b', type: 'SendMessageNode', name: '送', config: { template_id: 'x' }, outputs: { sent: 'a' } },
  ],
})

describe('duration', () => {
  it('解析與組合', () => {
    expect(parseDuration('PT30M')).toEqual({ amount: 30, unit: 'M' })
    expect(parseDuration('P3D')).toEqual({ amount: 3, unit: 'D' })
    expect(parseDuration('PT2H')).toEqual({ amount: 2, unit: 'H' })
    expect(parseDuration('PT1H30M')).toBeNull()
    expect(buildDuration(2, 'H')).toBe('PT2H')
    expect(buildDuration(1, 'D')).toBe('P1D')
    expect(durationText('P1D')).toBe('1 天')
    expect(durationText(undefined)).toBe('未設定')
  })
})

describe('條件值', () => {
  it('數字、陣列與文字', () => {
    expect(parseCondValue('GT', '500')).toBe(500)
    expect(parseCondValue('EQ', '500')).toBe('500')
    expect(parseCondValue('IN', 'a, b')).toEqual(['a', 'b'])
    expect(condValueText(['a', 'b'])).toBe('a,b')
    expect(condValueText(undefined)).toBe('')
  })
})

describe('節點操作', () => {
  it('新節點 id 符合規則且不重複', () => {
    const d = base()
    const id = newId(d, 'WaitDelayNode')
    expect(id).toMatch(ID_RE)
    expect(d.nodes.some((n) => n.id === id)).toBe(false)
    expect(makeNode(d, 'SplitTestNode').config.variants).toHaveLength(2)
  })
  it('刪除節點時移除指向它的連線', () => {
    const d = removeNode(base(), 'a')
    expect(d.nodes.find((n) => n.id === 'start')!.outputs).toEqual({})
    expect(d.nodes.find((n) => n.id === 'b')!.outputs).toEqual({})
  })
  it('連線、出口、入口', () => {
    const n = setOutput(base().nodes[0], 'next', '')
    expect(n.outputs).toEqual({})
    expect(outputSpecs({ ...base().nodes[1], type: 'WaitForEventNode', config: { timeout: null } }).map((s) => s.port)).toEqual(['occurred'])
    expect(incomingOf(base(), 'a').map((x) => x.id).sort()).toEqual(['b', 'start'])
    expect(linkTargets(base(), 'a').map((x) => x.id)).toEqual(['b'])
  })
  it('摘要與文字清單', () => {
    expect(summaryOf(base().nodes[1])).toBe('等 1 小時')
    expect(summaryOf(base().nodes[2])).toBe('範本 x')
    expect(textOutline(base())).toHaveLength(3)
  })
})

describe('迴圈與排版', () => {
  it('找到回邊', () => {
    expect(findLoops(base())).toEqual([{ from: 'b', to: 'a' }])
    const d = base()
    d.nodes[2].outputs = {}
    expect(findLoops(d)).toEqual([])
  })
  it('只補缺少的座標，已有的保留', () => {
    const l = fillLayout(base(), { nodes: { a: { x: 777, y: 5 } } })
    expect(l.nodes.a).toEqual({ x: 777, y: 5 })
    expect(Object.keys(l.nodes).sort()).toEqual(['a', 'b', 'start'])
    expect(placeNew(l, 'a', ['start', 'a', 'b']).x).toBeGreaterThan(777)
  })
})

describe('friendlyTime', () => {
  const now = new Date(2026, 10, 12, 12, 0)
  it('今天、昨天、同年、跨年', () => {
    expect(friendlyTime(new Date(2026, 10, 12, 9, 5).toISOString(), now)).toBe('今天 09:05')
    expect(friendlyTime(new Date(2026, 10, 11, 9, 5).toISOString(), now)).toBe('昨天 09:05')
    expect(friendlyTime(new Date(2026, 5, 1, 9, 5).toISOString(), now)).toBe('06-01 09:05')
    expect(friendlyTime(new Date(2025, 5, 1, 9, 5).toISOString(), now)).toBe('2025-06-01 09:05')
    expect(friendlyTime(null)).toBe('')
  })
})
