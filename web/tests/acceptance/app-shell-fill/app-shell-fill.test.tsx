// @vitest-environment jsdom
/**
 * app-shell-fill 驗收測試（qa 撰寫，worker 不可改）。
 * 依 docs/ui/journey-platform.md「外框撐滿」與 docs/ui/enrollment-trace.md「麵包屑」。
 * jsdom 不排版：以解析 app.css 規則＋DOM 結構驗證；像素由階段 uat 實測（D-069）。
 */
import { cleanup, screen, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'
import { mockApi, resetEnv, start, teardown, user } from '../shell/helpers'

const here = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(resolve(here, '../../../src/styles/app.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')

type Rule = { sel: string; decls: Record<string, string>; maxw: number | null }

function parse(src: string): Rule[] {
  const rules: Rule[] = []
  const block = (text: string, maxw: number | null) => {
    let i = 0
    while (i < text.length) {
      const open = text.indexOf('{', i)
      if (open < 0) break
      const head = text.slice(i, open).trim()
      let depth = 1
      let j = open + 1
      while (j < text.length && depth > 0) {
        if (text[j] === '{') depth++
        else if (text[j] === '}') depth--
        j++
      }
      const body = text.slice(open + 1, j - 1)
      if (head.startsWith('@media')) {
        const m = /max-width:\s*(\d+)px/.exec(head)
        block(body, m ? Number(m[1]) : maxw)
      } else if (!head.startsWith('@')) {
        const decls: Record<string, string> = {}
        for (const d of body.split(';')) {
          const k = d.indexOf(':')
          if (k > 0) decls[d.slice(0, k).trim().toLowerCase()] = d.slice(k + 1).trim().toLowerCase()
        }
        for (const s of head.split(',')) rules.push({ sel: s.trim().replace(/\s+/g, ' '), decls, maxw })
      }
      i = j
    }
  }
  block(src, null)
  return rules
}
const rules = parse(css)

/** selector 在視窗寬度 w（null＝只看 base）生效時，某屬性最後出現的值 */
function val(sel: string, prop: string, w: number | null = null): string | undefined {
  const hit = rules.filter((r) => r.sel === sel && prop in r.decls && (r.maxw === null || (w !== null && w <= r.maxw)))
  return hit.at(-1)?.decls[prop]
}

describe('外框撐滿視窗（桌面，base 規則）', () => {
  it('.shell：min-height 100dvh；無外框線、無圓角；不再是 640px 卡片', () => {
    const all = rules.filter((r) => r.sel === '.shell' && r.maxw === null)
    expect(all.length).toBeGreaterThan(0)
    expect(css).toMatch(/\.shell\s*\{[^}]*min-height:\s*100dvh/)
    for (const r of all) {
      expect(r.decls['min-height']).not.toBe('640px')
      expect(r.decls.border ?? 'none').toMatch(/^(none|0)/)
      expect(r.decls['border-radius'] ?? '0').toMatch(/^0/)
    }
  })

  it('body 不留白：base padding 為 0 或未設，margin 0', () => {
    expect(val('body', 'padding') ?? '0').toMatch(/^0/)
    expect(val('body', 'margin')).toMatch(/^0/)
  })

  it('.side：sticky、top 0、高度 100dvh、overflow-y auto、無圓角', () => {
    expect(val('.side', 'position')).toBe('sticky')
    expect(val('.side', 'top')).toMatch(/^0/)
    expect(css).toMatch(/\.side\s*\{[^}]*height:\s*100dvh/)
    expect(val('.side', 'overflow-y')).toBe('auto')
    expect(val('.side', 'border-radius') ?? '0').toMatch(/^0/)
  })

  it('.page：min-width 0（長字串／寬表格不撐出橫向捲動）', () => {
    expect(val('.page', 'min-width')).toMatch(/^0/)
  })
})

describe('窄螢幕（375px、500px）', () => {
  for (const w of [375, 500]) {
    it(`${w}px：外框單欄填滿、頂列 sticky 且無圓角、body 仍不留白`, () => {
      expect(val('.shell', 'grid-template-columns', w)).toMatch(/^minmax\(0,\s*1fr\)$/)
      expect(val('.top', 'position', w)).toBe('sticky')
      expect(val('.top', 'top', w)).toMatch(/^0/)
      expect(val('.top', 'border-radius', w) ?? '0').toMatch(/^0/)
      expect(val('body', 'padding', w) ?? '0').toMatch(/^0/)
      expect(val('.shell', 'border', w) ?? 'none').toMatch(/^(none|0)/)
      expect(val('.page', 'min-width', w)).toMatch(/^0/)
    })
  }

  it('窄螢幕 .page 保有自己的 padding（內容不貼邊）', () => {
    expect(val('.page', 'padding', 375)).toBe('var(--space-4)')
  })
})

describe('登入頁容器', () => {
  it('body 不留白後仍置中：.login-wrap 為 grid＋place-items center，滿版高，自帶 padding', () => {
    expect(val('.login-wrap', 'display')).toBe('grid')
    expect(val('.login-wrap', 'place-items')).toBe('center')
    expect(val('.login-wrap', 'min-height')).toMatch(/100d?vh/)
    expect(val('.login-wrap', 'padding')).toBeDefined()
  })
})

describe('麵包屑規則（CSS）', () => {
  it('ol、li 都是 flex 並 align-items:center；li 最小高 32px；ol 行高 1.4；縱向 gap 0', () => {
    expect(val('.crumbs ol', 'display')).toBe('flex')
    expect(val('.crumbs ol', 'align-items')).toBe('center')
    expect(val('.crumbs ol', 'line-height')).toBe('1.4')
    expect(val('.crumbs ol', 'gap')).toBe('0 var(--space-2)')
    expect(val('.crumbs li', 'display')).toBe('flex')
    expect(val('.crumbs li', 'align-items')).toBe('center')
    expect(val('.crumbs li', 'min-height')).toBe('32px')
  })

  it('連結不比 li 高：桌面 32px；640px 以下 li 與 a 皆為 44px（或 --touch-target）', () => {
    expect(val('.crumbs a', 'min-height')).toBe('32px')
    const touch = /^(44px|var\(--touch-target\))$/
    expect(val('.crumbs li', 'min-height', 640)).toMatch(touch)
    expect(val('.crumbs a', 'min-height', 640)).toMatch(touch)
  })

  it('分隔符用 li + li::before，替代文字為空（content: "/" / ""）', () => {
    expect(css).toMatch(/\.crumbs li \+ li::before\s*\{[^}]*content:\s*"\/"\s*\/\s*""/)
  })
})

describe('麵包屑結構（DOM）', () => {
  afterEach(() => {
    teardown()
    cleanup()
    resetEnv()
  })

  it('受眾頁（單段）：nav > ol > li，唯一一段是目前頁、非連結、aria-current=page', async () => {
    mockApi({ me: user() })
    start('/audience')
    const nav = await screen.findByRole('navigation', { name: '麵包屑' })
    const ol = nav.querySelector(':scope > ol')
    expect(ol).not.toBeNull()
    const lis = ol!.querySelectorAll(':scope > li')
    expect(lis.length).toBe(1)
    expect(lis[0].getAttribute('aria-current')).toBe('page')
    expect(lis[0].querySelector('a')).toBeNull()
    expect(nav.textContent).toBe('受眾')
  })

  it('修改密碼頁（兩段）：前段是連結，最後一段 aria-current=page 且非連結；DOM 文字沒有分隔符', async () => {
    mockApi({ me: user() })
    start('/account/password')
    const nav = await screen.findByRole('navigation', { name: '麵包屑' })
    const lis = nav.querySelectorAll(':scope > ol > li')
    expect(lis.length).toBe(2)
    expect(within(lis[0] as HTMLElement).getByRole('link', { name: '旅程' })).toBeTruthy()
    expect(lis[0].getAttribute('aria-current')).toBeNull()
    expect(lis[1].getAttribute('aria-current')).toBe('page')
    expect(lis[1].querySelector('a')).toBeNull()
    expect(nav.textContent).toBe('旅程修改密碼')
    expect(nav.textContent).not.toMatch(/[/›>]/)
  })
})
