import { describe, expect, it } from 'vitest'
import { isoDurationText, stepTitle } from '../../../src/plain'
import { formatDuration } from '../../../src/format'

describe('plain 邊界', () => {
  it('非法 ISO 期間原樣回傳', () => {
    expect(isoDurationText('P')).toBe('P')
    expect(isoDurationText('PT')).toBe('PT')
  })
  it('等待缺 delay 退回型別名', () => expect(stepTitle('WaitDelayNode', null)).toBe('等待'))
  it('formatDuration 59 秒', () => expect(formatDuration(59_999)).toBe('不到 1 分鐘'))
})
