/**
 * canvas-connect-drag：節點庫與未接出口的樣式規則（讀 app.css 原文，jsdom 不算版面）。假設見 helpers.tsx（.nodelib、data-connected）。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('樣式規則（app.css）', () => {
  const css = readFileSync(new URL('../../../src/styles/app.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
  /** 取出某 max-width 斷點區塊的內容 */
  function media(max: number): string {
    const out: string[] = []
    const re = /@media[^{]*max-width:\s*(\d+)px[^{]*\{/g
    let m: RegExpExecArray | null
    while ((m = re.exec(css))) {
      let depth = 1
      let i = re.lastIndex
      while (i < css.length && depth > 0) { depth += css[i] === '{' ? 1 : css[i] === '}' ? -1 : 0; i++ }
      if (Number(m[1]) === max) out.push(css.slice(re.lastIndex, i - 1))
    }
    return out.join('\n')
  }
  const rule = (src: string, sel: RegExp) =>
    [...src.matchAll(/([^{}]+)\{([^}]*)\}/g)].filter((m) => m[1].split(',').some((s) => sel.test(s.trim()))).map((m) => m[2]).join(';')
  const base = css.replace(/@media[^{]*\{(?:[^{}]*\{[^}]*\})*[^{}]*\}/g, '')

  it('節點庫 .nodelib：≥1101px 寬 216px；≤1100px 收成 72px 圖示軌（canvas-uat-fix：64px 放不下「A/B 分流」）', () => {
    expect(rule(base, /^\.nodelib$/)).toMatch(/width:\s*216px/)
    const r = rule(media(1100), /\.nodelib$/)
    expect(r).toMatch(/(^|[;\s])width:\s*72px/)
    expect(r).not.toMatch(/(^|[;\s])width:\s*64px/)
  })

  it('≤1100px 圖示軌：項目撐滿軌寬；名稱可換行最多 2 行（無 nowrap、word-break: keep-all），節點庫不可水平捲動', () => {
    const m = media(1100)
    const item = rule(m, /^\.nl-item$/)
    expect(item, '1100 斷點要有 .nl-item 規則').not.toBe('')
    expect(item).toMatch(/width:\s*100%|align-self:\s*stretch/)
    const name = rule(m, /^\.nl-tx b$/)
    expect(name, '1100 斷點要有 .nl-tx b 規則').not.toBe('')
    expect(name).not.toMatch(/white-space:\s*nowrap/)
    expect(name).toMatch(/word-break:\s*keep-all/)
    expect(name).toMatch(/text-align:\s*center/)
    const lib = rule(m, /\.nodelib$/)
    expect(lib).not.toMatch(/overflow(-x)?:\s*(auto|scroll)/)
    expect(lib).toMatch(/overflow-x:\s*hidden|overflow:\s*hidden/)
  })

  it('≤640px：節點庫變上方橫排（flex row、可橫向捲動）並有右緣漸層', () => {
    const r = media(640)
    const body = rule(r, /\.nodelib$/)
    expect(body, '640 斷點要有 .nodelib 規則').not.toBe('')
    expect(body).toMatch(/flex-direction:\s*row|display:\s*flex/)
    expect(body).toMatch(/overflow-x:\s*(auto|scroll)/)
    expect(r).toMatch(/\.nodelib[^{]*(::after|fade)/)
  })

  it('未接出口 [data-connected="false"]：橘色（warning）虛線', () => {
    const body = [...css.matchAll(/([^{}]*data-connected="false"[^{}]*)\{([^}]*)\}/g)].map((m) => m[2]).join(';')
    expect(body, '要有 [data-connected="false"] 的規則').not.toBe('')
    expect(body).toMatch(/dashed|dash/)
    expect(body).toMatch(/warning/)
  })

  it('不再有頂部工具列樣式（.tb-full、.addmenu 已移除）', () => {
    expect(css).not.toMatch(/\.tb-full/)
    expect(css).not.toMatch(/\.addmenu/)
  })
})
