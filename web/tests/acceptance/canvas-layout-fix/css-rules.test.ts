/**
 * canvas-layout-fix：樣式規則（jsdom 不算版面，改讀 app.css 原文；uat F-2、F-4）。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('../../../src/styles/app.css', import.meta.url), 'utf8')

/** 拿掉 @media 區塊，只留全寬度都適用的規則 */
function topLevel(src: string): string {
  let out = ''
  let depth = 0
  let inMedia = false
  for (let i = 0; i < src.length; i++) {
    if (depth === 0 && src.startsWith('@media', i)) inMedia = true
    const ch = src[i]
    if (ch === '{') depth++
    if (ch === '}') {
      depth--
      if (depth === 0 && inMedia) { inMedia = false; continue }
    }
    if (!inMedia) out += ch
  }
  return out
}
const base = topLevel(css)

describe('F-2 事件篩選／條件的欄位列在 360px 側欄也要欄位一行、比較＋值一行', () => {
  it('.sentence 預設（不限視窗寬）就用兩列排法，欄位自成一列，不是四欄並排', () => {
    const rule = /(?:^|\})\s*\.sentence\s*\{([^}]*)\}/m.exec(base)?.[1] ?? ''
    expect(rule).toMatch(/grid-template-areas/)
    expect(rule).not.toMatch(/grid-template-columns:\s*minmax\(0,\s*1\.3fr\)\s+minmax\(0,\s*1fr\)\s+minmax\(0,\s*1fr\)/)
    expect(base).toMatch(/\.sentence\s*>\s*\.sf\s*\{[^}]*grid-area:\s*f/)
  })
  it('條件節點的 .cond-line 讓欄位下拉獨佔一行（比較與值在下一行）', () => {
    const rules = [...base.matchAll(/([^{}]*\.cond-line[^{}]*)\{([^}]*)\}/g)].map((m) => `${m[1]}{${m[2]}}`).join('\n')
    expect(rules).toMatch(/flex-basis:\s*100%|flex:\s*1 1 100%|grid-template-areas|width:\s*100%/)
  })
})

describe('F-4 旅程頁標題不畫聚焦外框', () => {
  it('[tabindex="-1"]:focus 一律不畫外框（程式聚焦直接載入頁面時 :focus-visible 也會成立）', () => {
    const rule = /\[tabindex="-1"\]:focus\s*(?:,[^{]*)?\{([^}]*)\}/.exec(base)?.[1] ?? ''
    expect(rule).toMatch(/outline:\s*none/)
    expect(rule).toMatch(/box-shadow:\s*none/)
    expect(base).not.toMatch(/\[tabindex="-1"\]:focus:not\(:focus-visible\)/)
  })
})
