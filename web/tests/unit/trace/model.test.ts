import { describe, expect, it } from 'vitest'
import type { EnrollmentDetail, JourneyVersion } from '../../../src/api/types'
import { buildSteps, buildSummary, customerName, diagnosticText, upcomingSteps } from '../../../src/pages/trace/model'

process.env.TZ = 'Asia/Taipei'
const NOW = new Date('2026-10-18T03:00:00Z')

const def = {
  entry_node: 'start',
  nodes: [
    { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'w' } },
    { id: 'w', type: 'WaitDelayNode', name: null, config: { duration: 'PT2H' }, outputs: { next: 'e' } },
    { id: 'e', type: 'EndNode', name: null, config: {}, outputs: {} },
  ],
} as unknown as JourneyVersion

const d = {
  enrollment_id: 'E1', status: 'WAITING', current_node_id: 'w', current_node_type: 'WaitDelayNode', current_node_name: null,
  enrolled_at: '2026-10-18T02:00:00Z', as_of: '2026-10-18T03:00:00Z', terminated_at: null, masked_paths: [],
  wake_condition: { type: 'TIMER', wake_at: '2026-10-18T04:00:00Z', event_name: null, signal_name: null, timeout_at: null },
  trigger_snapshot: { profile_snapshot: { name: '王小明' } },
  trace: [{ seq: 1, node_id: 'w', node_type: 'WaitDelayNode', node_name: null, entered_at: '2026-10-18T02:00:00Z', exited_at: null, out_port: null, decision_reason: '', evaluated: {} }],
  delivery_history: [],
} as unknown as EnrollmentDetail

describe('trace model', () => {
  it('upcoming 沿出口往下，終態沒有', () => {
    expect(upcomingSteps(d, def).map((u) => u.title)).toEqual(['旅程結束'])
    expect(upcomingSteps({ ...d, status: 'COMPLETED' }, def)).toEqual([])
  })
  it('steps：目前步驟標題由定義的期間組出', () => {
    const s = buildSteps(d, def, NOW)
    expect(s.map((x) => [x.title, x.kind])).toEqual([['等 2 小時', 'now'], ['旅程結束', 'next']])
  })
  it('summary：定義取不到時降級', () => {
    const vm = buildSummary(d, null, 'failed', NOW)
    expect(vm.rows[1].hint).toContain('暫時無法顯示後續步驟')
  })
  it('姓名與診斷資訊不含個資', () => {
    expect(customerName(d)).toEqual({ name: '王小明', masked: false })
    expect(diagnosticText(d, NOW)).not.toContain('王小明')
  })
})
