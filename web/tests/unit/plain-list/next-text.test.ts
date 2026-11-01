import { describe, expect, it } from 'vitest'
import type { EnrollmentSummary } from '../../../src/api/types'
import { nextText, stepText } from '../../../src/pages/EnrollmentBrowsePage'

const row = (type: string, name: string | null) =>
  ({ current_node_type: type, current_node_name: name }) as EnrollmentSummary

describe('列表白話文字', () => {
  it('下一步依狀態', () => {
    expect(nextText('WAITING')).toBe('時間到會自動繼續')
    expect(nextText('ACTIVE')).toBe('處理中')
    expect(nextText('FAILED')).toBe('需要技術人員處理')
    expect(nextText('COMPLETED')).toBe('—')
    expect(nextText('ABORTED')).toBe('—')
  })
  it('目前步驟不顯示節點型別', () => {
    expect(stepText(row('EndNode', null))).toBe('旅程結束')
    expect(stepText(row('SendMessageNode', '歡迎信'))).toBe('發送訊息：歡迎信')
    expect(stepText(row('StartNode', null))).toBe('旅程開始')
  })
})
