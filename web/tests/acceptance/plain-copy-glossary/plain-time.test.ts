/**
 * plain-copy-glossary 驗收測試（qa 撰寫，worker 不可改）。對照 plain.html#time。
 *
 * 介面（worker 照此實作）：`web/src/format.ts` 新增具名匯出
 *   - formatPlainTime(iso: string, now: Date): string
 *       過去今天內  「今天 10:00（4 小時前）」（不足 1 小時「N 分鐘前」，不足 1 分鐘「剛剛」）
 *       過去昨天    「昨天 09:10」
 *       今年更早    「10/18（日）10:00」
 *       跨年        「2025/12/30（二）10:00」
 *       未來今天內  「今天 18:00（約 4 小時後）」
 *       明天        以「明天 10:00」開頭
 *       明天以後    「10/24（六）14:00（約 3 天後）」
 *       無法解析    原字串回傳，不丟例外
 *   - formatDuration(ms: number): string  「1 小時」「2 天 3 小時」「不到 1 分鐘」
 * 今天／昨天／明天以本地時區的日曆日判斷（本測試 TZ=Asia/Taipei）。既有函式不變。
 */
import { describe, expect, it } from 'vitest'
import { formatDuration, formatLocal, formatPlainTime } from '../../../src/format'

process.env.TZ = 'Asia/Taipei'

// 現在 = 2026-10-21（三）14:00 本地
const NOW = new Date('2026-10-21T06:00:00Z')

describe('formatPlainTime', () => {
  it('過去、今天內：今天 + 相對時間', () => {
    expect(formatPlainTime('2026-10-21T02:00:00Z', NOW)).toBe('今天 10:00（4 小時前）')
  })
  it('過去、今天內不足一小時／一分鐘', () => {
    expect(formatPlainTime('2026-10-21T05:30:00Z', NOW)).toBe('今天 13:30（30 分鐘前）')
    expect(formatPlainTime('2026-10-21T05:59:50Z', NOW)).toBe('今天 13:59（剛剛）')
  })
  it('昨天', () => {
    expect(formatPlainTime('2026-10-20T01:10:00Z', NOW)).toBe('昨天 09:10')
  })
  it('今年更早：月/日（週）時間', () => {
    expect(formatPlainTime('2026-10-18T02:00:00Z', NOW)).toBe('10/18（日）10:00')
  })
  it('跨年帶年份', () => {
    expect(formatPlainTime('2025-12-30T02:00:00Z', NOW)).toBe('2025/12/30（二）10:00')
  })
  it('未來、今天內', () => {
    expect(formatPlainTime('2026-10-21T10:00:00Z', NOW)).toBe('今天 18:00（約 4 小時後）')
  })
  it('明天', () => {
    expect(formatPlainTime('2026-10-22T02:00:00Z', NOW).startsWith('明天 10:00')).toBe(true)
  })
  it('明天以後：月/日（週）時間（約 N 天後）', () => {
    expect(formatPlainTime('2026-10-24T06:00:00Z', NOW)).toBe('10/24（六）14:00（約 3 天後）')
  })
  it('日界線依本地日曆日，不是 24 小時：本地 00:30 時，前一晚 23:30 是昨天', () => {
    const now = new Date('2026-10-21T16:30:00Z') // 本地 10/22 00:30
    expect(formatPlainTime('2026-10-21T15:30:00Z', now)).toBe('昨天 23:30')
    expect(formatPlainTime('2026-10-21T16:10:00Z', now)).toBe('今天 00:10（20 分鐘前）')
  })
  it('日界線：本地 23:30 時，隔天 00:10 是明天', () => {
    const now = new Date('2026-10-21T15:30:00Z') // 本地 23:30
    expect(formatPlainTime('2026-10-21T16:10:00Z', now).startsWith('明天 00:10')).toBe(true)
  })
  it('無法解析時原樣回傳，不丟例外', () => {
    expect(formatPlainTime('not-a-date', NOW)).toBe('not-a-date')
    expect(formatPlainTime('', NOW)).toBe('')
  })
})

describe('formatDuration', () => {
  it.each([
    [30_000, '不到 1 分鐘'],
    [0, '不到 1 分鐘'],
    [5 * 60_000, '5 分鐘'],
    [3_600_000, '1 小時'],
    [90 * 60_000, '1 小時 30 分鐘'],
    [(2 * 24 + 3) * 3_600_000, '2 天 3 小時'],
    [3 * 24 * 3_600_000, '3 天'],
  ])('%d ms -> %s', (ms, text) => {
    expect(formatDuration(ms)).toBe(text)
  })
  it('負值或 NaN 不丟例外', () => {
    expect(typeof formatDuration(-5)).toBe('string')
    expect(typeof formatDuration(Number.NaN)).toBe('string')
  })
})

describe('既有函式行為不變', () => {
  it('formatLocal', () => {
    expect(formatLocal('2026-10-21T02:00:00Z')).toBe('2026-10-21 10:00')
    expect(formatLocal('2026-10-21T02:00:00Z', true)).toBe('2026-10-21 10:00:00')
    expect(formatLocal('bad')).toBe('bad')
  })
})
