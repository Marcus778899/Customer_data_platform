// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { EnrollmentDetail } from '../../api/types'
import { Advanced } from './Advanced'
import { localizeIsoInText } from '../../plain'

describe('原因句只顯示本地時間（#147）', () => {
  it('文字內的 ISO UTC 轉為本地時間，不留 Z 結尾的原值', () => {
    const out = localizeIsoInText('已於 2026-11-01T08:30:00Z 結束；2026-11-02T01:00:00.123Z 逾時')
    expect(out).not.toMatch(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}.*Z/)
    expect(out).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
  })
})

describe('進階資訊結束原因（#147）', () => {
  it('termination_reason 內的 ISO UTC 轉本地', () => {
    const d = {
      enrollment_id: 'e1', customer_id: 'c1', journey_id: 'j1', journey_version: 1, status: 'ENDED',
      enrolled_at: '2026-11-01T08:00:00Z', terminated_at: '2026-11-01T09:00:00Z', as_of: '2026-11-01T09:00:00Z',
      termination_reason: '於 2026-11-01T08:30:00Z 逾時', wake_condition: null, variables: {},
      trigger_snapshot: { event_name: 'x', occurred_at: '2026-11-01T08:00:00Z', properties: {}, profile_snapshot: {} }, trace: [], delivery_history: [], masked_paths: [],
    } as unknown as EnrollmentDetail
    render(<Advanced d={d} />)
    const dd = screen.getByText('結束原因（系統文字）').nextElementSibling as HTMLElement
    expect(dd.textContent).not.toContain('T08:30:00Z')
    expect(dd.textContent).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
  })
})
