// @vitest-environment jsdom
/**
 * journey-customer-ui：窄螢幕與無障礙重點（docs/ui/journey-platform.md「窄螢幕與無障礙」）。
 * jsdom 不排版、不評估 media query：窄螢幕用「結構」（table.stack＋td[data-label]，同 FIX-08）與 app.css 的 640px 規則驗證；
 * 真實 375px／800px 版面與對比由 qa B 以瀏覽器實測。
 */
import { cleanup, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  PID, ROUTES, backend, enrollmentSummary, gotoNav, journeySummary, json, page, profileDetail, profileSummary,
  resetEnv, start, teardown,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const here = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(resolve(here, '../../../src/styles/app.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')

/** 取 @media (max-width: <=640px) 區塊內文 */
function narrowBlocks(src: string): string[] {
  const out: string[] = []
  const re = /@media[^{]*max-width:\s*(\d+)px[^{]*\{/g
  let m: RegExpExecArray | null
  while ((m = re.exec(src))) {
    let depth = 1
    let i = re.lastIndex
    while (i < src.length && depth > 0) { depth += src[i] === '{' ? 1 : src[i] === '}' ? -1 : 0; i++ }
    if (Number(m[1]) <= 640) out.push(src.slice(re.lastIndex, i - 1))
  }
  return out
}

function expectCards(table: HTMLElement) {
  expect(table.classList.contains('stack')).toBe(true)
  const cells = [...table.querySelectorAll('tbody td')]
  expect(cells.length).toBeGreaterThan(0)
  for (const td of cells) expect(td.getAttribute('data-label')).toBeTruthy()
}

describe('窄螢幕（<= 640px：表格轉卡片）', () => {
  it('旅程列表、客戶搜尋結果、客戶頁旅程紀錄、Enrollment 瀏覽的表格都是 table.stack 且每個 td 有 data-label', async () => {
    backend({
      'GET /api/v1/journeys': () => json(page([journeySummary()])),
      'GET /api/v1/profiles/search': () => json({ items: [profileSummary(), profileSummary({ profile_id: 'p2' })] }),
      [`GET /api/v1/profiles/${PID}`]: () => json(profileDetail()),
      [`GET /api/v1/profiles/${PID}/enrollments`]: () => json(page([enrollmentSummary()])),
      'GET /api/v1/enrollments': () => json(page([enrollmentSummary()])),
    })
    start('/')
    await gotoNav('旅程')
    expectCards(await screen.findByRole('table'))
    await gotoNav('客戶')
    await userEvent.type(await screen.findByLabelText('搜尋客戶'), 'M-20418')
    await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
    const results = await screen.findByRole('table')
    expectCards(results)
    await userEvent.click(results.querySelector('tbody a') as HTMLElement)
    expectCards(await screen.findByRole('table', { name: /參加過/ }))
    cleanup()
    start(ROUTES.browse)
    expectCards(await screen.findByRole('table'))
  })

  it('app.css 的 640px 規則讓互動元件高 >= 44px（含表格內連結）、圖可左右捲動', () => {
    const narrow = narrowBlocks(css).join('\n')
    expect(narrow).toMatch(/min-height:\s*44px/)
    // 表格內的連結（旅程名稱、客戶 ID）也要達 44px：設計稿用 .tap；接受 .tap／table a／td a
    expect(narrow).toMatch(/(\.tap|table\s+a|td\s+a)[^{}]*\{[^}]*min-height:\s*44px/)
    expect(css).toMatch(/overflow-x:\s*(auto|scroll)/)
  })
})

describe('無障礙', () => {
  it('客戶搜尋：輸入框有 label；結果完成後焦點在結果標題；載入中用 role=status、錯誤用 role=alert（已於各畫面測試）', async () => {
    backend({
      'GET /api/v1/profiles/search': () => json({ items: [profileSummary(), profileSummary({ profile_id: 'p2' })] }),
    })
    start('/')
    await gotoNav('客戶')
    const box = await screen.findByLabelText('搜尋客戶')
    expect(box.tagName).toBe('INPUT')
    // 輸入說明以 aria-describedby 關聯
    expect(box.getAttribute('aria-describedby')).toBeTruthy()
    await userEvent.type(box, 'M-20418')
    await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
    await screen.findByRole('table')
    expect(['H1', 'H2', 'H3']).toContain(document.activeElement?.tagName)
  })

  it('旅程列表與客戶頁的表格有 caption 與 scope=col 的欄標題', async () => {
    backend({
      'GET /api/v1/journeys': () => json(page([journeySummary()])),
    })
    start('/')
    await gotoNav('旅程')
    const table = await screen.findByRole('table')
    expect(table.querySelector('caption')?.textContent).toBeTruthy()
    for (const th of table.querySelectorAll('thead th')) expect(th.getAttribute('scope')).toBe('col')
  })

  it('旅程列表的旅程名稱是連結（可鍵盤操作）、狀態一律有文字', async () => {
    backend({ 'GET /api/v1/journeys': () => json(page([journeySummary()])) })
    start('/')
    await gotoNav('旅程')
    const link = await screen.findByRole('link', { name: '棄單挽回' })
    link.focus()
    expect(document.activeElement).toBe(link)
    expect(link.closest('tr')!.textContent).toContain('進行中')
  })
})
