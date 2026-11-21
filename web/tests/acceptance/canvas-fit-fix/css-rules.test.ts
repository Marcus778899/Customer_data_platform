/**
 * canvas-fit-fix：樣式規則（jsdom 不算版面，改讀 app.css／tokens.css 原文）。
 * F-24 事件篩選「比較」下拉在 360px 側欄不可被截；F-25 旅程頁節點卡內字級縮放後不可小於 10px；
 * F-20 編輯頁沒有開面板時，畫布不留右側空欄（版面需靠真瀏覽器，這裡只擋 CSS 仍無條件保留面板欄）。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('../../../src/styles/app.css', import.meta.url), 'utf8')
const tokens = readFileSync(new URL('../../../src/styles/tokens.css', import.meta.url), 'utf8')

const rulesFor = (src: string, selector: RegExp) =>
  [...src.matchAll(/([^{}]+)\{([^}]*)\}/g)].filter((m) => m[1].split(',').some((s) => selector.test(s.trim()))).map((m) => m[2])

/** 把 `var(--x)`、rem、px 換成 px；認不得的格式回 NaN（修正請用這三種寫法） */
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

describe('F-24 事件篩選的「比較」下拉不被截斷', () => {
  it('.sentence 的比較欄在 360px 側欄（內寬約 326px）至少 150px 寬，放得下「（選擇比較）」', () => {
    const grid = rulesFor(css, /^\.sentence$/).map((r) => /grid-template-columns:\s*([^;]+);/.exec(r)?.[1]).find(Boolean) ?? ''
    const areas = rulesFor(css, /^\.sentence$/).map((r) => /grid-template-areas:\s*([^;]+);/.exec(r)?.[1]).find(Boolean) ?? ''
    const tracks: Array<{ fr: number; px: number }> = [...grid.matchAll(/minmax\(\s*0\s*,\s*([\d.]+)fr\s*\)|([\d.]+)fr|([\d.]+)px/g)].map((m) =>
      m[3] ? { fr: 0, px: Number(m[3]) } : { fr: Number(m[1] ?? m[2]), px: 0 },
    )
    expect(tracks.length, `讀不到 .sentence 的欄寬：${grid}`).toBeGreaterThan(0)
    const inner = 326
    const gap = 4
    const fixed = tracks.reduce((acc, t) => acc + t.px, 0) + gap * (tracks.length - 1)
    const frSum = tracks.reduce((acc, t) => acc + t.fr, 0)
    const widthOf = (i: number) => {
      const t = tracks[i]
      return !t ? 0 : t.px > 0 ? t.px : (t.fr * (inner - fixed)) / frSum
    }
    // 「o」（比較）在第二列佔哪幾欄
    const row2 = (/"[^"]*"\s*"([^"]*)"/.exec(areas)?.[1] ?? '').trim().split(/\s+/)
    const cols = row2.map((a, i) => (a === 'o' ? i : -1)).filter((i) => i >= 0)
    expect(cols.length, `.sentence 第二列沒有 o：${areas}`).toBeGreaterThan(0)
    const w = cols.reduce((a, i) => a + widthOf(i), 0) + gap * (cols.length - 1)
    const minWidth = Math.max(0, ...rulesFor(css, /^\.sentence\s*>\s*select$/).map((r) => px(/(?:^|;|\s)min-width:\s*([^;]+)/.exec(r)?.[1] ?? '0')).filter((n) => !Number.isNaN(n)))
    expect(Math.max(w, minWidth), `比較欄約 ${Math.round(w)}px`).toBeGreaterThanOrEqual(150)
  })
})

describe('F-25 旅程頁節點卡的字縮放後仍讀得到', () => {
  it('卡內最小字級（n-cat／n-type／n-state／n-stat）乘上最小縮放（75%）後 >= 10px', () => {
    // node-card-unify：三處縮放下限統一 75%（docs/ui/canvas-interaction.md §2），不再讀 FlowGraph 的常數
    const minScale = 0.75
    const sizes = ['n-cat', 'n-type', 'n-state', 'n-stat'].map((c) => {
      const rule = rulesFor(css, new RegExp(`^\\.node\\s+\\.${c}$`)).map((r) => /font-size:\s*([^;]+);/.exec(r)?.[1]).find(Boolean)
      return { c, px: px(rule ?? '') }
    })
    for (const s of sizes) expect(Number.isNaN(s.px), `.node .${s.c} 的 font-size 要寫成 var(--…)／rem／px`).toBe(false)
    const smallest = Math.min(...sizes.map((s) => s.px))
    expect(smallest * minScale, `最小字級 ${smallest}px × 最小縮放 ${minScale}`).toBeGreaterThanOrEqual(10)
  })
})

describe('F-20 編輯頁沒有開面板時畫布不留空欄', () => {
  it('.edbody 不可在寬螢幕無條件保留右側面板欄（面板空時要讓畫布佔滿）', () => {
    const wide = /@media\s*\(min-width:\s*1101px\)\s*\{([^@]*?\.edbody\s*\{[^}]*\})/.exec(css)?.[1] ?? ''
    const unconditional = /\.edbody\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s+var\(--panel-width\)/.test(wide)
    const conditional = /\.edbody[^{]*(:has\(|\.has-panel|\.panel-open|\.with-panel|\[data-panel)/.test(css)
    expect(unconditional && !conditional, '.edbody 目前無論有沒有開面板都保留 360px 面板欄').toBe(false)
  })
})
