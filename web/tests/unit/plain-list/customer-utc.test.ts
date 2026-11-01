import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const src = readFileSync(new URL('../../../src/pages/CustomerPage.tsx', import.meta.url), 'utf8')

describe('客戶頁不在畫面顯示 UTC', () => {
  it('沒有「同時顯示 UTC 原值」勾選與表格內 UTC 文字', () => {
    expect(src).not.toContain('同時顯示 UTC')
    expect(src).not.toContain('（UTC ')
    expect(src).not.toContain('UtcContext')
  })
  it('時間保留 UTC 原值在 dateTime 與 title', () => {
    expect(src).toContain('dateTime={iso} title={iso}')
  })
})
