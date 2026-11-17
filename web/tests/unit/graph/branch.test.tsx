// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JourneyVersion, TraceEntry } from '../../../src/api/types'
import { buildModel, deriveOverlay, loopNodeIds } from '../../../src/graph/model'
import { NodePanel, portText } from '../../../src/graph/NodePanel'

afterEach(cleanup)

const def = {
  entry_node: 's',
  layout: null,
  nodes: [
    { id: 's', type: 'StartNode', name: null, config: {}, outputs: { next: 'a' } },
    { id: 'a', type: 'WaitForEventNode', name: '等', config: {}, outputs: { occurred: 'g', timeout: 'm' } },
    { id: 'm', type: 'SendMessageNode', name: '提醒', config: {}, outputs: { sent: 'a' } },
    { id: 'g', type: 'GoalNode', name: null, config: {}, outputs: {} },
  ],
} as unknown as JourneyVersion

const t = (seq: number, node_id: string, out_port: string | null, exited_at: string | null = 'x') =>
  ({ seq, node_id, node_type: 'X', node_name: null, entered_at: 'x', exited_at, out_port, decision_reason: '', evaluated: {} }) as unknown as TraceEntry

describe('loopNodeIds', () => {
  it('只有能走回自己的節點在迴圈裡；指向不存在的節點不當掉', () => {
    expect([...loopNodeIds(def)].sort()).toEqual(['a', 'm'])
    const broken = { ...def, nodes: [{ id: 'z', type: 'X', name: null, config: {}, outputs: { next: 'nope' } }] } as unknown as JourneyVersion
    expect(loopNodeIds(broken).size).toBe(0)
  })
})

describe('deriveOverlay / buildModel', () => {
  it('次數：多次經過的節點帶 passes，目前位置帶 visit；統計掛在節點上', () => {
    const trace = [t(1, 'a', 'timeout'), t(2, 'm', 'sent'), t(3, 'a', null, null)]
    const ov = deriveOverlay(def, trace, 'WAITING')!
    expect(ov.currentVisit).toBe(2)
    const m = buildModel(def, ov, [{ node_id: 'a', entered: 3, visits: 4, current: 1, out_ports: {} }])
    const a = m.nodes.find((n) => n.id === 'a')!.data
    expect(a.visit).toBe(2)
    expect(a.passes).toBe(2)
    expect(a.stat).toEqual({ entered: 3, current: 1 })
    expect(m.nodes.find((n) => n.id === 'm')!.data.passes).toBeUndefined()
  })
  it('終態：離開後的節點只帶 passes，沒有目前位置', () => {
    const ov = deriveOverlay(def, [t(1, 'a', 'timeout'), t(2, 'm', 'sent'), t(3, 'a', 'occurred'), t(4, 'g', null)], 'GOAL_MET')!
    const a = buildModel(def, ov).nodes.find((n) => n.id === 'a')!.data
    expect(a.passes).toBe(2)
    expect(a.visit).toBeUndefined()
  })
})

describe('NodePanel', () => {
  const node = def.nodes[1]
  it('有統計：人數、次數、目前、各出口；迴圈說明；關閉', async () => {
    const onClose = vi.fn()
    render(<NodePanel def={def} node={node} stat={{ node_id: 'a', entered: 5, visits: 7, current: 1, out_ports: { occurred: 4 } }} onClose={onClose} />)
    const text = screen.getByRole('complementary').textContent ?? ''
    expect(text).toContain('這個節點在迴圈裡')
    expect(text).toMatch(/事件發生 → GoalNode 4/)
    expect(text).toMatch(/逾時 → 提醒 0/)
    await userEvent.click(screen.getByRole('button', { name: '關閉面板' }))
    expect(onClose).toHaveBeenCalled()
  })
  it('沒有統計：只有設定', () => {
    render(<NodePanel def={def} node={node} stat={undefined} onClose={() => undefined} />)
    const text = screen.getByRole('complementary').textContent ?? ''
    expect(text).not.toContain('人數')
    expect(text).toContain('出口')
  })
  it('出口白話；未知出口原樣', () => {
    expect(portText('yes')).toBe('是')
    expect(portText('x_y')).toBe('x_y')
  })
})
