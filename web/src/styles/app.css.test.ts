import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('./app.css', import.meta.url), 'utf8')

describe('app.css 規則（#144、#148、#178）', () => {
  it('程式聚焦（tabindex=-1）在非 focus-visible 時不顯示外框，鍵盤的 :focus-visible 規則仍在', () => {
    expect(css).toMatch(/\[tabindex="-1"\]:focus:not\(:focus-visible\)\s*\{[^}]*outline:\s*none[^}]*box-shadow:\s*none/)
    expect(css).toMatch(/^:focus-visible\s*\{[^}]*box-shadow:\s*var\(--focus-ring\)/m)
  })
  it('麵包屑分隔符替代文字為空，讀屏不會唸出', () => {
    const rule = /\.crumbs li \+ li::before\s*\{([^}]*)\}/.exec(css)?.[1] ?? ''
    expect(rule).toContain('content: "/" / ""')
  })
  it('搜尋按鈕與輸入框緊鄰接合（無間距、圓角相接）', () => {
    expect(css).toMatch(/\.search-row\s*\{[^}]*gap:\s*0/)
    expect(css).toMatch(/\.search-row input\s*\{[^}]*border-right:\s*0/)
  })
  it('客戶頁旅程表三欄：table-layout: fixed，欄寬合計 100%', () => {
    expect(css).toMatch(/table\.enr\s*\{[^}]*table-layout:\s*fixed/)
    const widths = [...css.matchAll(/table\.enr th:nth-child\((\d)\)\s*\{\s*width:\s*(\d+)%/g)]
    expect(widths.map((m) => m[1])).toEqual(['1', '2', '3'])
    expect(widths.reduce((n, m) => n + Number(m[2]), 0)).toBe(100)
  })
  it('搜尋按鈕與輸入框同列對齊並限制寬度', () => {
    expect(css).toMatch(/\.search-row\s*\{[^}]*display:\s*flex[^}]*align-items:\s*stretch/)
    expect(css).toMatch(/\.search-big\s*\{[^}]*max-width/)
  })
})
