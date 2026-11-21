import { describe, expect, it } from 'vitest'
import type { Def } from './model'
import { canTarget, connect, freePortCount, freePorts, nudgeFree, portName } from './model'

const def = (): Def => ({
  name: 't', description: null, settings: {}, entry_node: 'start',
  nodes: [
    { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'src' } },
    { id: 'src', type: 'EventSourceNode', name: null, config: { event_name: 'e' }, outputs: {} },
    { id: 'cond', type: 'ConditionNode', name: null, config: {}, outputs: { yes: 'end' } },
    { id: 'msg', type: 'SendMessageNode', name: null, config: {}, outputs: { sent: 'end' } },
    { id: 'end', type: 'EndNode', name: null, config: {}, outputs: {} },
  ],
})

describe('連線輔助', () => {
  it('portName 去掉括號說明；freePorts 不算選填出口', () => {
    expect(portName({ port: 'skipped', label: '被略過時（例如客戶已退訂）' })).toBe('被略過時')
    const d = def()
    expect(freePorts(d.nodes[2]).map((s) => s.port)).toEqual(['no'])
    expect(freePorts(d.nodes[3])).toEqual([])
    expect(freePortCount(d)).toBe(2)
  })
  it('canTarget 排除開始與進入條件', () => {
    expect(canTarget(def().nodes[0])).toBe(false)
    expect(canTarget(def().nodes[1])).toBe(false)
    expect(canTarget(def().nodes[4])).toBe(true)
    expect(canTarget(undefined)).toBe(false)
  })
  it('connect 取代同出口原連線；自己、進入條件、不存在的出口都拒絕', () => {
    const d = def()
    expect(connect(d, 'cond', 'yes', 'msg')?.nodes[2].outputs).toEqual({ yes: 'msg' })
    expect(connect(d, 'cond', 'no', 'end')?.nodes[2].outputs).toEqual({ yes: 'end', no: 'end' })
    expect(connect(d, 'cond', 'yes', 'cond')).toBeNull()
    expect(connect(d, 'cond', 'yes', 'src')).toBeNull()
    expect(connect(d, 'cond', 'bogus', 'end')).toBeNull()
    expect(connect(d, 'nope', 'yes', 'end')).toBeNull()
  })
  it('nudgeFree 重疊就往下挪', () => {
    const layout = { nodes: { a: { x: 0, y: 0 } } }
    expect(nudgeFree(layout, ['a'], { x: 500, y: 0 })).toEqual({ x: 500, y: 0 })
    expect(nudgeFree(layout, ['a'], { x: 10, y: 10 }).y).toBeGreaterThan(120)
  })
})
