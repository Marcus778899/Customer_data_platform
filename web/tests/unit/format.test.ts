import { describe, expect, it } from 'vitest'
import { childPath, formatLocal, indexPath, isPlainObject, utcOffsetLabel } from '../../src/format'

process.env.TZ = 'Asia/Taipei'

describe('format', () => {
  it('formatLocal 轉成本地時區，可選秒', () => {
    expect(formatLocal('2026-10-18T02:00:05Z')).toBe('2026-10-18 10:00')
    expect(formatLocal('2026-10-18T02:00:05Z', true)).toBe('2026-10-18 10:00:05')
    expect(formatLocal('2026-10-18T17:00:00Z')).toBe('2026-10-19 01:00')
  })

  it('無法解析的時間原樣顯示', () => {
    expect(formatLocal('not-a-time')).toBe('not-a-time')
  })

  it('utcOffsetLabel', () => {
    expect(utcOffsetLabel(new Date('2026-10-18T00:00:00Z'))).toBe('UTC+08:00')
  })

  it('遮罩路徑組法與契約 §4.4 一致', () => {
    expect(childPath('', 'variables')).toBe('variables')
    expect(childPath('trace[1].evaluated', 'phone')).toBe('trace[1].evaluated.phone')
    expect(indexPath('variables.items', 0)).toBe('variables.items[0]')
  })

  it('isPlainObject', () => {
    expect(isPlainObject({})).toBe(true)
    expect(isPlainObject([])).toBe(false)
    expect(isPlainObject(null)).toBe(false)
  })
})
