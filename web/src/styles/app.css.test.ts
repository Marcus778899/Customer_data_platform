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
  it('客戶頁旅程表在寬螢幕不逐字換行、時間欄可換行', () => {
    expect(css).toMatch(/table\.enr th\s*\{\s*white-space:\s*nowrap/)
    expect(css).toMatch(/table\.enr td\.nw[^{]*\{\s*white-space:\s*normal/)
  })
  it('搜尋按鈕與輸入框同列對齊並限制寬度', () => {
    expect(css).toMatch(/\.search-row\s*\{[^}]*display:\s*flex[^}]*align-items:\s*stretch/)
    expect(css).toMatch(/\.search-big\s*\{[^}]*max-width/)
  })
})
