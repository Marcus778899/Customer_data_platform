import { describe, expect, it } from 'vitest'
import { isoDurationText, stepTitle } from '../../../src/plain'
import { formatDuration, formatPlainTime } from '../../../src/format'

describe('plain 邊界', () => {
  it('非法 ISO 期間原樣回傳', () => {
    expect(isoDurationText('P')).toBe('P')
    expect(isoDurationText('PT')).toBe('PT')
  })
  it('等待缺 delay 退回型別名', () => expect(stepTitle('WaitDelayNode', null)).toBe('等待'))
  it('formatDuration 59 秒', () => expect(formatDuration(59_999)).toBe('不到 1 分鐘'))
})

describe('review 修正', () => {
  it('WaitDelayNode 無 delay 保留 node_name', () => {
    expect(stepTitle('WaitDelayNode', '等 3 天')).toBe('等 3 天')
    expect(stepTitle('WaitDelayNode', '等 3 天', null)).toBe('等 3 天')
  })
  it('WaitDelayNode delay 無法解析時用名稱，不顯示 ISO 原字串', () => {
    expect(stepTitle('WaitDelayNode', '等 3 天', 'P1W')).toBe('等 3 天')
    expect(stepTitle('WaitDelayNode', null, 'P1W')).toBe('等待')
  })
})

describe('formatPlainTime 選項與日曆日', () => {
  process.env.TZ = 'Asia/Taipei'
  const NOW = new Date('2026-10-21T06:00:00Z')
  it('relative=false 不附括號', () => {
    expect(formatPlainTime('2026-10-21T02:00:00Z', NOW, { relative: false })).toBe('今天 10:00')
    expect(formatPlainTime('2026-10-20T01:10:00Z', NOW, { relative: false })).toBe('昨天 09:10')
    expect(formatPlainTime('2026-10-21T10:00:00Z', NOW, { relative: false })).toBe('今天 18:00')
    expect(formatPlainTime('2026-10-22T02:00:00Z', NOW, { relative: false })).toBe('明天 10:00')
    expect(formatPlainTime('2026-10-24T06:00:00Z', NOW, { relative: false })).toBe('10/24（六）14:00')
    expect(formatPlainTime('2025-12-30T02:00:00Z', NOW, { relative: false })).toBe('2025/12/30（二）10:00')
  })
  it('約 N 天後用日曆日差：本地 10/21 23:00 -> 10/23 01:00 是 2 天後', () => {
    const now = new Date('2026-10-21T15:00:00Z')
    expect(formatPlainTime('2026-10-22T17:00:00Z', now)).toBe('10/23（五）01:00（約 2 天後）')
  })
})
