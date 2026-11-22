// @vitest-environment jsdom
/**
 * canvas-add-reveal-fix F-59（uat 第九輪）：375 寬軌跡頁因隱藏的 caption.sr（absolute、寬 100%）撐出 33px 水平捲動。
 * jsdom 不排版：把 app.css 在 375px 生效的規則（含 @media max-width）依 element.matches 套到實際渲染的元素上，
 * 算出最終 position／width／display，驗「不會撐寬頁面」且「caption 仍在、可被輔助技術讀到」。
 * 真實 375 寬無水平捲動由 uat 目視。
 */
import { cleanup, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { completed, delivery, heading, openTrace, resetEnv, teardown } from '../trace-plain-rewrite/fixtures'

const here = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(resolve(here, '../../../src/styles/app.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')

type Rule = { sel: string; decls: Record<string, string>; order: number }

/** 只收 base 與 max-width >= 375 的區塊（375px 會生效） */
function parse(src: string): Rule[] {
  const rules: Rule[] = []
  let order = 0
  const block = (text: string) => {
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
        const max = /max-width:\s*(\d+)px/.exec(head)
        const min = /min-width:\s*(\d+)px/.exec(head)
        if ((max || min) && (!max || Number(max[1]) >= 375) && (!min || Number(min[1]) <= 375)) block(body)
      } else if (!head.startsWith('@')) {
        const decls: Record<string, string> = {}
        for (const d of body.split(';')) {
          const k = d.indexOf(':')
          if (k > 0) decls[d.slice(0, k).trim().toLowerCase()] = d.slice(k + 1).trim().toLowerCase().replace(/\s*!important/, '')
        }
        for (const s of head.split(',')) rules.push({ sel: s.trim().replace(/\s+/g, ' '), decls, order: order++ })
      }
      i = j
    }
  }
  block(src)
  return rules
}
const rules = parse(css)
const spec = (s: string) =>
  (s.match(/[.#:][\w-]+|\[[^\]]*\]/g) ?? []).length * 10 + (s.match(/(^|[\s>+~])[a-z][\w-]*/gi) ?? []).length

function computed(el: Element, prop: string): string | undefined {
  const hit = rules
    .filter((r) => {
      try {
        return prop in r.decls && el.matches(r.sel)
      } catch {
        return false
      }
    })
    .sort((a, b) => spec(a.sel) - spec(b.sel) || a.order - b.order)
  return hit.at(-1)?.decls[prop]
}

beforeEach(resetEnv)
afterEach(() => {
  cleanup()
  teardown()
})

const CAPTION_TEXT = /這位客戶的訊息發送紀錄/
const openWithDeliveries = async () => {
  openTrace({}, completed({ delivery_history: [delivery(1)] }))
  await heading()
  return screen.findByText(CAPTION_TEXT)
}

describe('F-59：375 寬軌跡頁的 caption 不撐寬頁面', () => {
  it('發送紀錄表的 caption：仍在 DOM、有文字、沒被 display:none／visibility:hidden 拿掉（輔助技術讀得到）', async () => {
    const cap = await openWithDeliveries()
    expect(cap.tagName).toBe('CAPTION')
    expect(cap.closest('table')).toBeTruthy()
    expect(computed(cap, 'display')).not.toBe('none')
    expect(computed(cap, 'visibility')).not.toBe('hidden')
    expect(cap.getAttribute('aria-hidden')).not.toBe('true')
  })

  it('375px 生效的規則下，隱藏用元素（caption／.sr／.sr-only）不可是 absolute／fixed 且寬度大於 1px（否則以 static 位置撐出頁寬）', async () => {
    await openWithDeliveries()
    const els = [...document.querySelectorAll('.sr, .sr-only, caption')]
    expect(els.length).toBeGreaterThan(0)
    for (const el of els) {
      const pos = computed(el, 'position')
      if (pos !== 'absolute' && pos !== 'fixed') continue
      const label = `${el.tagName.toLowerCase()}.${[...el.classList].join('.')} position=${pos} width=${computed(el, 'width')}`
      expect(computed(el, 'width'), `${label}：375px 下寬度被撐成容器寬，起點 33px 就超出視窗`).toMatch(/^(0|1px|0px)$/)
      const mw = computed(el, 'max-width')
      if (mw !== undefined) expect(mw, label).toMatch(/^(0|1px|0px)$/)
    }
  })

  it('caption 在 375px 的最終樣式：不是「absolute 且寬 100%」', async () => {
    const cap = await openWithDeliveries()
    const bad = computed(cap, 'position') === 'absolute' && computed(cap, 'width') === '100%'
    expect(bad, `position=${computed(cap, 'position')} width=${computed(cap, 'width')}`).toBe(false)
  })
})
