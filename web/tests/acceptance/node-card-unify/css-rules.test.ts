/**
 * node-card-unify：樣式規則（jsdom 不算版面，讀 app.css 原文）。docs/ui/canvas-interaction.md §2。
 * 卡片寬 200；名稱 20px／600 最多 2 行；類別、摘要、人數 16px；連線標籤 16px；內距上下 10、右 12、左 14；
 * 右緣漸層 48px（捲到底消失）。app.css 的 var(--x) 先查 app.css 自己的定義，再查 tokens.css。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync(new URL('../../../src/styles/app.css', import.meta.url), 'utf8')
const tokens = readFileSync(new URL('../../../src/styles/tokens.css', import.meta.url), 'utf8')

const rulesFor = (src: string, selector: RegExp) =>
  [...src.matchAll(/([^{}]+)\{([^}]*)\}/g)].filter((m) => m[1].split(',').some((s) => selector.test(s.trim()))).map((m) => m[2])
const decl = (body: string, prop: string) => new RegExp(`(?:^|[;\\s])${prop}:\\s*([^;]+)`).exec(body)?.[1]?.trim()
const declOf = (selector: RegExp, prop: string) => rulesFor(css, selector).map((r) => decl(r, prop)).filter(Boolean).at(-1) ?? ''

function px(value: string, depth = 0): number {
  const v = value.trim()
  const rem = /^([\d.]+)rem$/.exec(v)
  if (rem) return Number(rem[1]) * 16
  const p = /^([\d.]+)px$/.exec(v)
  if (p) return Number(p[1])
  const variable = /^var\(\s*(--[\w-]+)\s*(?:,\s*([^)]+))?\)$/.exec(v)
  if (variable && depth < 6) {
    const re = new RegExp(`${variable[1]}\\s*:\\s*([^;]+);`)
    const def = re.exec(css)?.[1] ?? re.exec(tokens)?.[1]
    return px(def ?? variable[2] ?? '', depth + 1)
  }
  return Number.NaN
}

describe('卡片尺寸與內距', () => {
  it('唯讀圖 .node 寬 200（不再是 148）', () => {
    expect(px(declOf(/^\.node$/, 'width'))).toBe(200)
  })
  it('唯讀圖 .node 內距：上下 10、右 12、左 14', () => {
    const pad = declOf(/^\.node$/, 'padding').split(/\s+/).map((s) => px(s))
    expect(pad, `padding：${declOf(/^\.node$/, 'padding')}`).toEqual([10, 12, 10, 14])
  })
  it('編輯畫布 .cnode 內距同一組（上下 10、右 12、左 14）', () => {
    const pad = declOf(/^\.cnode$/, 'padding').split(/\s+/).map((s) => px(s))
    expect(pad, `padding：${declOf(/^\.cnode$/, 'padding')}`).toEqual([10, 12, 10, 14])
  })
})

describe('字級（三處相同）', () => {
  const sel = (cls: string) => [new RegExp(`^\\.node\\s+\\.${cls}$`), new RegExp(`^\\.cnode\\s+\\.${cls}$`)]
  it('名稱 20px／600', () => {
    for (const [i, re] of sel('n-name').entries()) {
      expect(px(declOf(re, 'font-size')), `${i === 0 ? '.node' : '.cnode'} .n-name`).toBe(20)
      expect(Number(declOf(re, 'font-weight'))).toBeGreaterThanOrEqual(600)
    }
  })
  it('類別 16px', () => {
    for (const re of sel('n-cat')) expect(px(declOf(re, 'font-size'))).toBe(16)
  })
  it('摘要、類型、人數、狀態 16px', () => {
    expect(px(declOf(/^\.cnode\s+\.n-sum$/, 'font-size'))).toBe(16)
    for (const c of ['n-type', 'n-stat', 'n-state']) expect(px(declOf(new RegExp(`^\\.node\\s+\\.${c}$`), 'font-size')), c).toBe(16)
  })
  it('人數數字等寬粗體', () => {
    const rules = rulesFor(css, /^\.node\s+\.n-stat\s+b$/).join(';')
    expect(rules).toMatch(/font-variant-numeric:\s*tabular-nums|font-family:\s*var\(--font-mono\)/)
    expect(Number(decl(rules, 'font-weight') ?? 0) >= 700 || /bold/.test(decl(rules, 'font-weight') ?? ''), '人數數字要粗體').toBe(true)
  })
  it('連線標籤 16px（唯讀圖與編輯畫布）', () => {
    expect(px(declOf(/^\.graph\s+\.e-label$/, 'font-size'))).toBe(16)
    expect(px(declOf(/^\.canvas-wrap\s+\.e-label$/, 'font-size'))).toBe(16)
  })
})

describe('截斷：名稱 2 行、摘要 1 行', () => {
  it('名稱用 line-clamp 2（不是 nowrap 單行）', () => {
    for (const re of [/^\.node\s+\.n-name$/, /^\.cnode\s+\.n-name$/]) {
      const body = rulesFor(css, re).join(';')
      expect(body).toMatch(/line-clamp:\s*2/)
      expect(decl(body, 'white-space') ?? 'normal').not.toBe('nowrap')
      expect(body).toMatch(/overflow:\s*hidden/)
    }
  })
  it('摘要與類型維持單行加「…」', () => {
    for (const re of [/^\.cnode\s+\.n-sum$/, /^\.node\s+\.n-type$/]) {
      const body = rulesFor(css, re).join(';')
      expect(body).toMatch(/white-space:\s*nowrap/)
      expect(body).toMatch(/text-overflow:\s*ellipsis/)
    }
  })
})

describe('右緣漸層規則統一為 .cvfade', () => {
  it('.cvfade 寬 48px、不擋指標、用 linear-gradient；有 hidden 時不顯示', () => {
    const body = rulesFor(css, /^\.cvfade$/).join(';')
    expect(px(decl(body, 'width') ?? '')).toBe(48)
    expect(body).toMatch(/pointer-events:\s*none/)
    expect(body).toMatch(/linear-gradient/)
    expect(rulesFor(css, /^\.cvfade\[hidden\]$/).join(';')).toMatch(/display:\s*none/)
  })
  it('沒有別的 fade 規則寬度不是 48px', () => {
    for (const m of css.matchAll(/([^{}]*fade[^{}]*)\{([^}]*)\}/g)) {
      const w = decl(m[2], 'width')
      if (w) expect(px(w), `規則 ${m[1].trim()} 的寬度`).toBe(48)
    }
  })
})
