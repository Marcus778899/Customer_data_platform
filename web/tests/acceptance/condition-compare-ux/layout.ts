/**
 * 斷點版面輔助（qa 撰寫）：jsdom 不算版面也不評估 @media，這裡把 app.css 依「視窗寬度」攤平
 * （只留該寬度適用的 @media (max-width／min-width) 區塊內容、var(--x) 換成 tokens.css 的值），
 * 注入文件後用 getComputedStyle 讀真實元素的層疊結果；比單看 CSS 原文更接近實際排版。
 */
import { readFileSync } from 'node:fs'

const read = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8')
const appCss = read('../../../src/styles/app.css')
const tokens = read('../../../src/styles/tokens.css')

function vars(): Record<string, string> {
  const out: Record<string, string> = {}
  for (const m of tokens.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) out[m[1]] ??= m[2].trim()
  return out
}

function resolveVars(css: string): string {
  const v = vars()
  let s = css
  for (let i = 0; i < 6; i++) {
    s = s.replace(/var\(\s*(--[\w-]+)\s*(?:,\s*([^)]+))?\)/g, (_m, name: string, fb?: string) => v[name] ?? fb ?? '0')
  }
  return s
}

/** 攤平成「在 width 寬度下」的樣式表文字（媒體區塊接在原位，保留來源順序以維持層疊） */
export function cssAt(width: number): string {
  let out = ''
  let i = 0
  const re = /@media\s*([^{]+)\{/g
  for (let m = re.exec(appCss); m; m = re.exec(appCss)) {
    out += appCss.slice(i, m.index)
    let depth = 1
    let j = re.lastIndex
    while (j < appCss.length && depth > 0) {
      if (appCss[j] === '{') depth++
      else if (appCss[j] === '}') depth--
      j++
    }
    const cond = m[1]
    const max = /max-width:\s*(\d+)px/.exec(cond)
    const min = /min-width:\s*(\d+)px/.exec(cond)
    const ok = (!max || width <= Number(max[1])) && (!min || width >= Number(min[1])) && (!!max || !!min)
    if (ok) out += appCss.slice(re.lastIndex, j - 1)
    i = j
    re.lastIndex = j
  }
  out += appCss.slice(i)
  return resolveVars(out)
}

export function withViewport<T>(width: number, fn: () => T): T {
  const style = document.createElement('style')
  style.setAttribute('data-test-viewport', String(width))
  style.textContent = cssAt(width)
  document.head.appendChild(style)
  try {
    return fn()
  } finally {
    style.remove()
  }
}

const px = (s: string) => {
  const m = /^(-?[\d.]+)px$/.exec(s.trim())
  return m ? Number(m[1]) : Number.NaN
}

/** 元素的固定寬：width、flex-basis、flex 簡寫任一寫成 Npx 就算 */
export function fixedWidth(el: Element): number {
  const cs = getComputedStyle(el)
  for (const v of [cs.width, cs.flexBasis, ...(cs.flex ? cs.flex.split(/\s+/) : [])]) {
    const n = px(v)
    if (!Number.isNaN(n)) return n
  }
  return Number.NaN
}
/** 元素的高度：height 或 min-height 取大 */
export function boxHeight(el: Element): number {
  const cs = getComputedStyle(el)
  const hs = [px(cs.height), px(cs.minHeight)].filter((n) => !Number.isNaN(n))
  return hs.length ? Math.max(...hs) : Number.NaN
}
/** 元素本身或其父層以直向排列（flex-direction: column），或自己整行寬（100%） */
export function stacked(el: Element): boolean {
  const cs = getComputedStyle(el)
  const p = el.parentElement ? getComputedStyle(el.parentElement) : null
  const col = !!p && /flex/.test(p.display) && (p.flexDirection === 'column' || p.display === 'block' || p.display === 'grid')
  const fullRow = cs.width === '100%' || /^100%$/.test(cs.flexBasis) || /(^|\s)100%(\s|$)/.test(cs.flex)
  return col || fullRow
}
