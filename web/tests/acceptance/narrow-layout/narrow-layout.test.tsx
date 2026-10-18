// @vitest-environment jsdom
/**
 * FIX-08 驗收測試（qa 撰寫，worker 不可改）。UAT v0.1.0 F-3：375px 窄螢幕版面。
 *
 * jsdom 不做排版、不評估 media query，無法量測實際寬度；改用可驗證的替代斷言：
 *   1. 解析 web/src/styles/app.css，檢查 375px 生效的樣式規則（對應根因）：
 *      a. table.stack 在窄螢幕被改成 display:block，<caption>（table-caption）失去所屬表格盒，
 *         被包成匿名窄欄，說明逐字換行。修法需讓 caption 在窄螢幕為 display:block（或等效、非 table-caption）。
 *      b. form.search 在窄螢幕為 column，但 .field 的 flex-basis 220px（.field-status 160px）變成「高度」基準，
 *         欄位間出現異常空白。修法需讓窄螢幕下欄位 flex-basis 為 auto（或表單不再用 column flex）。
 *   2. 桌面規則不變（base 規則維持原值）；DOM 結構仍為 table.stack > caption。
 * 真實排版（一行顯示、間距、無橫向捲動）仍須 qa B 用瀏覽器 375px 實測。
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../../../src/App'

const here = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(resolve(here, '../../../src/styles/app.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')

type Rule = { sel: string; decls: Record<string, string>; narrow: boolean; order: number }

function parse(src: string): Rule[] {
  const rules: Rule[] = []
  let order = 0
  const block = (text: string, narrow: boolean) => {
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
        // 只收會在 375px 生效的 max-width 區塊
        if (m && Number(m[1]) >= 375) block(body, true)
      } else if (!head.startsWith('@')) {
        const decls: Record<string, string> = {}
        for (const d of body.split(';')) {
          const k = d.indexOf(':')
          if (k > 0) decls[d.slice(0, k).trim().toLowerCase()] = d.slice(k + 1).trim().toLowerCase()
        }
        for (const s of head.split(',')) rules.push({ sel: s.trim().replace(/\s+/g, ' '), decls, narrow, order: order++ })
      }
      i = j
    }
  }
  block(src, false)
  return rules
}

const rules = parse(css)
const spec = (s: string) =>
  (s.match(/[.#:][\w-]+|\[[^\]]*\]/g) ?? []).length * 10 + (s.match(/(^|[\s>+~])[a-z][\w-]*/gi) ?? []).length

/** 以 selector 白名單算某屬性的最終值（specificity，再依出現順序）。narrow=true 含窄螢幕規則，false 只看 base */
function effective(selectors: string[], prop: string, narrow = true): string | undefined {
  const hit = rules
    .filter((r) => selectors.includes(r.sel) && (narrow || !r.narrow) && prop in r.decls)
    .sort((a, b) => spec(a.sel) - spec(b.sel) || a.order - b.order)
  return hit.at(-1)?.decls[prop]
}

/** 解析 flex 簡寫 / flex-basis，回傳 basis */
function basisOf(selectors: string[], narrow: boolean): string {
  const hit = rules
    .filter((r) => selectors.includes(r.sel) && (narrow || !r.narrow) && ('flex' in r.decls || 'flex-basis' in r.decls))
    .sort((a, b) => spec(a.sel) - spec(b.sel) || a.order - b.order)
  let basis = 'auto'
  for (const r of hit) {
    if ('flex' in r.decls) {
      const v = r.decls.flex.split(/\s+/)
      if (['none', 'auto', 'initial'].includes(v[0])) basis = 'auto'
      else basis = v.length >= 3 ? v[2] : '0%'
    }
    if ('flex-basis' in r.decls) basis = r.decls['flex-basis']
  }
  return basis
}

const FIELD = ['.field', 'form.search .field', 'form.search > .field', '.search .field']
const FIELD_STATUS = ['.field', '.field-status', 'form.search .field', 'form.search > .field', 'form.search .field-status', 'form.search > .field-status', '.search .field', '.search .field-status']
const FORM = ['form.search', '.search']
const CAPTION = ['table.stack caption', '.stack caption', 'table caption', 'caption', 'table.stack > caption']

describe('窄螢幕樣式規則（375px 生效）', () => {
  it('有 max-width 640px 的窄螢幕區塊（同 --breakpoint-narrow）', () => {
    expect(rules.some((r) => r.narrow)).toBe(true)
    expect(/@media\s*\(max-width:\s*640px\)/.test(css)).toBe(true)
  })

  it('F-3a 說明不逐字換行：table.stack 變 block 時，caption 在窄螢幕不可仍是 table-caption', () => {
    expect(effective(['table.stack'], 'display')).toBe('block') // 前提：表格轉卡片仍在
    const d = effective(CAPTION, 'display')
    expect(d, 'caption 在窄螢幕需明確設 display（block 等非 table-caption 值）').toBeDefined()
    expect(d).not.toBe('table-caption')
    expect(['block', 'flex', 'grid', 'inline-block', 'list-item']).toContain(d)
  })

  it('F-3a caption 窄螢幕不得被限制寬度或逐字斷行', () => {
    for (const v of [effective(CAPTION, 'width'), effective(CAPTION, 'max-width')]) {
      if (v !== undefined) expect(['100%', 'auto', 'none']).toContain(v)
    }
    expect(effective(CAPTION, 'writing-mode')).toBeUndefined()
    expect(effective(CAPTION, 'word-break')).not.toBe('break-all')
  })

  it('F-3b 查詢表單欄位間距：column 排列時欄位不以 220px/160px 當高度基準', () => {
    const dir = effective(FORM, 'flex-direction')
    const disp = effective(FORM, 'display') ?? 'flex'
    if (disp.includes('flex') && dir === 'column') {
      expect(basisOf(FIELD, true)).toMatch(/^(auto|content)$/)
      expect(basisOf(FIELD_STATUS, true)).toMatch(/^(auto|content)$/)
    }
    expect(effective(FORM, 'gap', false)).toBe('var(--space-3)')
  })

  it('F-3b 窄螢幕不得為欄位設大的固定 height / min-height', () => {
    for (const sel of FIELD_STATUS) {
      for (const p of ['height', 'min-height']) {
        const v = effective([sel], p)
        if (v !== undefined) expect(v).toMatch(/^(auto|44px|var\(--space-[0-6]\))$/)
      }
    }
  })

  it('無橫向捲動：窄螢幕規則不設過寬固定寬度；表格容器 .scroll 仍可內部捲動', () => {
    for (const r of rules.filter((x) => x.narrow)) {
      for (const p of ['width', 'min-width']) {
        const m = /^(\d+(\.\d+)?)px$/.exec(r.decls[p] ?? '')
        if (m) expect(Number(m[1]), `${r.sel} ${p}`).toBeLessThanOrEqual(375 - 32)
      }
    }
    expect(effective(['.scroll'], 'overflow-x', false)).toBe('auto')
    expect(effective(['table'], 'width', false)).toBe('100%')
  })

  it('既有窄螢幕行為保留：表頭隱藏、卡片、觸控目標 44px、dl.kv 單欄、body 縮 padding', () => {
    expect(effective(['table.stack thead'], 'position')).toBe('absolute')
    expect(effective(['.btn', 'input', 'select'], 'min-height')).toBe('44px')
    expect(effective(['dl.kv'], 'grid-template-columns')).toBe('1fr')
    expect(effective(['body'], 'padding')).toBe('var(--space-4)')
    expect(effective(['table.stack tr'], 'display')).toBe('block')
  })
})

describe('桌面寬度外觀不變（base 規則）', () => {
  it('表單與欄位', () => {
    expect(effective(['form.search'], 'display', false)).toBe('flex')
    expect(effective(['form.search'], 'flex-wrap', false)).toBe('wrap')
    expect(effective(['form.search'], 'gap', false)).toBe('var(--space-3)')
    expect(effective(['form.search'], 'align-items', false)).toBe('flex-end')
    expect(effective(['.field'], 'flex', false)).toBe('1 1 220px')
    expect(effective(['.field-status'], 'flex-basis', false)).toBe('160px')
  })
  it('表格與 caption 在桌面維持 table 預設（base 不覆寫 display）', () => {
    expect(effective(CAPTION, 'display', false)).toBeUndefined()
    expect(effective(['caption'], 'text-align', false)).toBe('left')
    expect(effective(['caption'], 'padding-bottom', false)).toBe('var(--space-2)')
    expect(effective(['table.stack'], 'display', false)).toBeUndefined()
    expect(effective(['.app'], 'max-width', false)).toBe('var(--content-max-width)')
  })
})

describe('DOM 結構（說明文字在 table.stack 的單一 caption 內）', () => {
  const PID = '01JCXYZPROFILE00000000001'
  const json = (b: unknown, s = 200) =>
    new Response(JSON.stringify(b), { status: s, headers: { 'Content-Type': 'application/json' } })
  const row = {
    enrollment_id: '01JCXYZENROLL000000000001', journey_id: 'J1', profile_id: PID,
    current_node_id: 'n1', current_node_type: 'WAIT', journey_version: 1, journey_name: '歡迎旅程',
    current_node_name: '等待', status: 'WAITING', enrolled_at: '2026-10-18T01:00:00Z',
    updated_at: '2026-10-18T01:00:00Z', terminated_at: null,
  }
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const u = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, 'http://localhost')
        if (u.pathname.endsWith('/auth/me')) return json({ username: 'support' })
        if (u.pathname.endsWith('/enrollments')) return json({ items: [row], next_cursor: null })
        return json({}, 404)
      }),
    )
    window.history.pushState({}, '', `/?q=${PID}`)
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('列表：說明為 table.stack 的 caption，文字完整在同一元素', async () => {
    render(<App />)
    await waitFor(() => expect(screen.getByText(/依進入時間新到舊/)).toBeTruthy())
    const cap = screen.getByText(/依進入時間新到舊/)
    expect(cap.tagName).toBe('CAPTION')
    expect(cap.parentElement?.tagName).toBe('TABLE')
    expect(cap.parentElement?.classList.contains('stack')).toBe(true)
    expect(cap.textContent).toBe('共 1 筆，依進入時間新到舊')
  })
})
