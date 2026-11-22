// @vitest-environment jsdom
/**
 * journey-header-save-delete：斷點下的實際排版（canvas-interaction.md §4「≤640px 標題換行，主要按鈕與「⋯」同列、44px」、§5 離開提醒「≤640px 直排」）。
 * jsdom 不算版面也不評估 @media：用 condition-compare-ux/layout.ts 把 app.css 依視窗寬度攤平、注入文件，對真實元素讀 getComputedStyle 的層疊結果，
 * 再判斷「標題區與動作區是否同列／直排」，不是只看 CSS 原文有沒有 44px 或 max-width。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { boxHeight, cssAt, withViewport } from '../condition-compare-ux/layout'
import {
  AMY, cleanup, dlgButton, leaveDialog, makeDirty, moreButton, openEditor, openJourney, editorBackend, resetEnv, screen, teardown,
  userEvent, itemNamed, openMenu,
} from './helpers'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const dir = (cs: CSSStyleDeclaration) => (cs.flexDirection || 'row')
const isFlex = (cs: CSSStyleDeclaration) => /flex/.test(cs.display)
const full = (el: Element) => {
  const cs = getComputedStyle(el)
  return cs.width === '100%' || cs.flexBasis === '100%' || /(^|\s)100%(\s|$)/.test(cs.flex)
}
const px = (v: string) => (/^[\d.]+px$/.test(v.trim()) ? Number(v) : Number.NaN)

/** 兩個兄弟區塊（a、b）在父層裡是不是上下堆疊（直排）：block、單欄 grid、flex column、或 flex-wrap 且其中一塊整列寬 */
function stackedIn(parent: Element, a: Element, b: Element): boolean {
  const cs = getComputedStyle(parent)
  if (isFlex(cs)) {
    if (dir(cs).startsWith('column')) return true
    return cs.flexWrap.startsWith('wrap') && (full(a) || full(b))
  }
  if (/grid/.test(cs.display)) return (cs.gridTemplateColumns || '').trim().split(/\s+/).filter(Boolean).length <= 1
  return true // block：子層自然上下排
}
/** a 與 b 的共同祖先下，各自所在的子節點 */
function split(a: Element, b: Element) {
  let p: Element | null = a.parentElement
  while (p && !p.contains(b)) p = p.parentElement
  if (!p) throw new Error('找不到共同祖先')
  const child = (x: Element) => { let c = x; while (c.parentElement !== p) c = c.parentElement!; return c }
  return { parent: p, a: child(a), b: child(b) }
}

async function header() {
  await openJourney({ me: AMY, kind: 'neverPublished' })
  const more = moreButton()!
  expect(more, '標頭要有「更多動作」').toBeTruthy()
  const h1 = screen.getByRole('heading', { level: 1 })
  const acts = more.parentElement!
  const primaries = [...acts.querySelectorAll<HTMLElement>('button, a')].filter((e) => e !== more && !e.closest('[role="menu"]'))
  expect(primaries.length, '標頭動作區至少一顆主要按鈕').toBeGreaterThan(0)
  return { more, h1, acts, primaries, ...split(h1, more) }
}

describe('輔助：攤平規則本身正確（避免測試恆過）', () => {
  it('max-width 區塊只在視窗寬度不超過時才併入', () => {
    expect(cssAt(375)).toMatch(/\.panel-collapse[^{]*\{[^}]*display:\s*none/)
    expect(cssAt(1440)).not.toMatch(/\.panel-collapse[^{]*\{[^}]*display:\s*none/)
  })
})

describe('旅程頁標頭', () => {
  it.each([1440, 768])('%ipx：標題區與動作區同一列（左標題、右動作），不換行堆疊', async (w) => {
    const h = await header()
    withViewport(w, () => {
      expect(stackedIn(h.parent, h.a, h.b)).toBe(false)
    })
  })

  it('375px：標題整列換行（標題區與動作區上下堆疊）；主要按鈕與「⋯」仍在同一列（動作區橫排）', async () => {
    const h = await header()
    withViewport(375, () => {
      expect(stackedIn(h.parent, h.a, h.b), '標題與動作區要上下堆疊').toBe(true)
      const cs = getComputedStyle(h.acts)
      expect(isFlex(cs), '動作區是 flex').toBe(true)
      expect(dir(cs).startsWith('column'), '主要按鈕與「⋯」同一列，不直排').toBe(false)
      for (const b of [...h.primaries, h.more]) expect(full(b), `「${b.textContent || b.getAttribute('aria-label')}」不獨佔整列`).toBe(false)
    })
  })

  it('375px：主要按鈕與「⋯」高度皆 ≥ 44px', async () => {
    const h = await header()
    withViewport(375, () => {
      for (const b of [...h.primaries, h.more]) {
        expect(boxHeight(b), `「${b.textContent || b.getAttribute('aria-label')}」高度`).toBeGreaterThanOrEqual(44)
      }
    })
  })

  it('375px：選單項（刪除旅程）高度 ≥ 44px', async () => {
    await openJourney({ me: AMY, kind: 'neverPublished' })
    const menu = await openMenu()
    const del = itemNamed(menu, '刪除旅程')!
    withViewport(375, () => expect(boxHeight(del)).toBeGreaterThanOrEqual(44))
  })

  it('標題在 375px 可換行：不是 white-space: nowrap／單行省略', async () => {
    const h = await header()
    withViewport(375, () => {
      const cs = getComputedStyle(h.h1)
      expect(cs.whiteSpace).not.toBe('nowrap')
    })
  })
})

describe('離開提醒對話框（375px 直排、按鈕 44px）', () => {
  async function open() {
    editorBackend()
    await openEditor()
    await makeDirty()
    await userEvent.click(document.querySelector<HTMLElement>('[aria-label="返回旅程頁"]')!)
    return leaveDialog()
  }
  it('375px：三顆按鈕直排（容器 column 或各自整列寬）且各 ≥ 44px', async () => {
    const d = await open()
    const btns = ['繼續編輯', '不儲存直接離開', '儲存並離開'].map((n) => dlgButton(d, n))
    withViewport(375, () => {
      for (const b of btns) {
        const col = dir(getComputedStyle(b.parentElement!)).startsWith('column') && isFlex(getComputedStyle(b.parentElement!))
        expect(col || full(b), `「${b.textContent}」要直排`).toBe(true)
        expect(boxHeight(b)).toBeGreaterThanOrEqual(44)
      }
    })
  })
  it('1440px：三顆按鈕橫排在同一列（不直排）', async () => {
    const d = await open()
    const btns = ['繼續編輯', '不儲存直接離開', '儲存並離開'].map((n) => dlgButton(d, n))
    withViewport(1440, () => {
      const cs = getComputedStyle(btns[0].parentElement!)
      expect(dir(cs).startsWith('column') && isFlex(cs)).toBe(false)
      for (const b of btns) expect(full(b)).toBe(false)
      expect(Number.isNaN(px(getComputedStyle(btns[0]).width)) || px(getComputedStyle(btns[0]).width) < 640).toBe(true)
    })
  })
})
