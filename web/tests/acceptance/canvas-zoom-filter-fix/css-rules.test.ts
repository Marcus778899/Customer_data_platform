/**
 * canvas-zoom-filter-fix：樣式規則（jsdom 不算版面，讀 app.css 原文）。
 * 字級補償（規格「畫布縮放」）：類別與摘要 1.05rem、名稱 1.3rem、連線標籤 17px；行高：類別與摘要 <= 1.25、名稱 <= 1.2；
 * 375 畫布高度規格 300px（設計稿 280px 為誤植）。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('../../../src/styles/app.css', import.meta.url), 'utf8')
const tokens = readFileSync(new URL('../../../src/styles/tokens.css', import.meta.url), 'utf8')

const rulesFor = (src: string, selector: RegExp) =>
  [...src.matchAll(/([^{}]+)\{([^}]*)\}/g)].filter((m) => m[1].split(',').some((s) => selector.test(s.trim()))).map((m) => m[2])
const decl = (body: string, prop: string) => new RegExp(`(?:^|[;\\s])${prop}:\\s*([^;]+)`).exec(body)?.[1]?.trim()

function px(value: string, depth = 0): number {
  const v = value.trim()
  const rem = /^([\d.]+)rem$/.exec(v)
  if (rem) return Number(rem[1]) * 16
  const p = /^([\d.]+)px$/.exec(v)
  if (p) return Number(p[1])
  const variable = /^var\(\s*(--[\w-]+)\s*(?:,\s*([^)]+))?\)$/.exec(v)
  if (variable && depth < 5) {
    const def = new RegExp(`${variable[1]}\\s*:\\s*([^;]+);`).exec(tokens)?.[1]
    return px(def ?? variable[2] ?? '', depth + 1)
  }
  return Number.NaN
}

/** 取出 `@media (max-width: Npx) { ... }` 區塊內容（處理巢狀大括號） */
function mediaBlocks(maxWidth: number): string[] {
  const out: string[] = []
  const re = new RegExp(`@media\\s*\\(max-width:\\s*${maxWidth}px\\)\\s*\\{`, 'g')
  for (let m = re.exec(css); m; m = re.exec(css)) {
    let depth = 1
    let i = re.lastIndex
    while (i < css.length && depth > 0) {
      if (css[i] === '{') depth++
      else if (css[i] === '}') depth--
      i++
    }
    out.push(css.slice(re.lastIndex, i - 1))
  }
  return out
}

describe('F-31 編輯畫布節點卡字級補償', () => {
  const fontMax = (sel: RegExp) => Math.max(...rulesFor(css, sel).map((r) => px(decl(r, 'font-size') ?? '')).filter((n) => !Number.isNaN(n)), 0)
  const lineMin = (sel: RegExp) =>
    Math.min(...rulesFor(css, sel).map((r) => Number(decl(r, 'line-height') ?? Number.NaN)).filter((n) => !Number.isNaN(n)), 99)

  it('名稱 >= 1.3rem、類別與摘要 >= 1.05rem', () => {
    expect(fontMax(/\.cnode\s+\.n-name$/)).toBeGreaterThanOrEqual(1.3 * 16)
    expect(fontMax(/\.cnode\s+\.n-cat$/)).toBeGreaterThanOrEqual(1.05 * 16)
    expect(fontMax(/\.cnode\s+\.n-sum$/)).toBeGreaterThanOrEqual(1.05 * 16)
  })
  it('行高收緊：類別、摘要 <= 1.25，名稱 <= 1.2（內容 <= 94px，文字不被截）', () => {
    expect(lineMin(/\.cnode\s+\.n-name$/)).toBeLessThanOrEqual(1.2)
    expect(lineMin(/\.cnode\s+\.n-cat$/)).toBeLessThanOrEqual(1.25)
    expect(lineMin(/\.cnode\s+\.n-sum$/)).toBeLessThanOrEqual(1.25)
  })
  it('連線標籤 >= 17px', () => {
    expect(fontMax(/\.e-label$/)).toBeGreaterThanOrEqual(17)
  })
})

describe('F-31 窄螢幕畫布高度', () => {
  it('<=640px（375 預覽）編輯畫布高度是 300px，不是 clamp(…52vh…)', () => {
    const heights = mediaBlocks(640).flatMap((b) => rulesFor(b, /\.canvas-wrap$/).map((r) => decl(r, 'height')).filter(Boolean))
    expect(heights.length, '640px 以下要有 .canvas-wrap 的 height').toBeGreaterThan(0)
    expect(heights.at(-1)).toBe('300px')
  })
})
