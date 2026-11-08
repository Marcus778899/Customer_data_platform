import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const read = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8')
const customer = read('../../../src/pages/CustomerPage.tsx')
const journey = read('../../../src/pages/JourneyPage.tsx')
const css = read('../../../src/styles/app.css')

describe('客戶頁表格與用詞', () => {
  it('畫面文字不出現內部編號', () => {
    const jsxText = customer.split('\n').filter((l) => !l.trim().startsWith('/**') && !l.includes('//'))
    expect(jsxText.join('\n')).not.toMatch(/<p[^>]*>[^<]*D-\d+/)
    expect(customer).not.toContain('瀏覽器時區（D-016）')
  })
  it('旅程頁連結用「旅程進度」，不出現 Enrollment 字樣', () => {
    expect(journey).toContain('>旅程進度</AppLink>')
    expect(journey).not.toContain('這個旅程的 Enrollment')
  })
  it('客戶頁左欄在寬螢幕縮窄讓出寬度', () => {
    expect(css).toContain('.cols.two.cust')
  })
  it('識別碼表與資料時間之間有間距', () => {
    expect(css).toContain('.cust .scroll > table.plain { margin-top: var(--space-4); }')
  })
})
