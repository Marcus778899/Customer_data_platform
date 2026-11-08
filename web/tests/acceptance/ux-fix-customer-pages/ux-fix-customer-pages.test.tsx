// @vitest-environment jsdom
/**
 * ux-fix-customer-pages 驗收（#144、#148、#178、#179、#188）。依 docs/ui/journey-builder.md UX 表與 DESIGN-05 批 1。
 * CSS 規則在 jsdom 不會套用，直接讀 app.css 原文檢查規則存在；行為（文案、結構）走畫面。
 */
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PID, ROUTES, backend, bodyText, enrollmentSummary, gotoNav, journeySummary, json, page, profileDetail, profileSummary,
  resetEnv, start, teardown,
} from '../journey-customer-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown(); vi.useRealTimers() })

const css = readFileSync(resolve(__dirname, '../../../src/styles/app.css'), 'utf-8')
/** 取出所有「選擇器 { 宣告 }」（@media 內以最內層區塊計） */
const rules = () => [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => ({ sel: m[1].trim(), body: m[2] }))
const rulesFor = (re: RegExp) => rules().filter((r) => re.test(r.sel))
const gapsOf = (body: string) => [...body.matchAll(/(?:^|[;\s])gap:\s*([^;]+)/g)].map((m) => m[1].trim())

const PENR = `/api/v1/profiles/${PID}/enrollments`
const rows = () => [
  enrollmentSummary({ current_node_name: 'Wait PT2H' }),
  enrollmentSummary({
    enrollment_id: 'e2', journey_name: '新會員歡迎', status: 'ACTIVE', current_node_id: 'send_1',
    current_node_type: 'SendMessageNode', current_node_name: '歡迎信',
  }),
  enrollmentSummary({
    enrollment_id: 'e3', journey_name: '舊旅程', status: 'COMPLETED', current_node_id: 'end_1',
    current_node_type: 'EndNode', current_node_name: '結束', terminated_at: '2026-09-04T06:31:00Z',
  }),
]
const routes = () => ({
  'GET /api/v1/profiles/search': () => json({ items: [profileSummary({ profile_id: PID, matched_by: 'PROFILE_ID' })] }),
  [`GET /api/v1/profiles/${PID}`]: () => json(profileDetail()),
  [`GET ${PENR}`]: () => json(page(rows())),
  'GET /api/v1/enrollments': () => json(page(rows())),
  'GET /api/v1/journeys': () => json(page([journeySummary()])),
})
async function openCustomer() {
  backend(routes())
  start('/')
  await gotoNav('客戶')
  await userEvent.type(await screen.findByLabelText('搜尋客戶'), PID)
  await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
  return screen.findByRole('table', { name: /參加過/ })
}
const dataRows = (t: HTMLElement) => within(t).getAllByRole('row').slice(1)

describe('#144 客戶頁旅程表', () => {
  it('三欄：旅程（含目前／最後位置）、狀態、進入與結束時間；每列 3 格', async () => {
    const t = await openCustomer()
    expect(within(t).getAllByRole('columnheader').map((h) => h.textContent?.trim())).toEqual(['旅程', '狀態', '進入與結束時間'])
    const r = dataRows(t)
    expect(r).toHaveLength(3)
    for (const row of r) expect(within(row).getAllByRole('cell')).toHaveLength(3)
    expect(within(r[0]).getAllByRole('cell')[0].textContent).toMatch(/現在在：/)
    expect(within(r[2]).getAllByRole('cell')[0].textContent).toMatch(/最後在：/)
  })

  it('未結束寫「尚未結束」、已結束寫結束時間；不再出現「—」佔位', async () => {
    const t = await openCustomer()
    const r = dataRows(t)
    expect(within(r[0]).getAllByRole('cell')[2].textContent).toContain('尚未結束')
    expect(within(r[2]).getAllByRole('cell')[2].textContent).not.toContain('尚未結束')
    expect(within(r[2]).getAllByRole('cell')[2].textContent).toContain('結束')
    expect(t.textContent).not.toContain('—')
  })

  it('app.css：旅程表 table-layout: fixed', () => {
    const hit = rulesFor(/table\.enr|\.enr\b/).filter((r) => /table-layout:\s*fixed/.test(r.body))
    expect(hit.length).toBeGreaterThan(0)
  })
})

describe('#179 步驟說法與軌跡頁一致', () => {
  it('客戶頁：「Wait PT2H」→「等 2 小時」；訊息「發送訊息：歡迎信」；結束「旅程結束」', async () => {
    const t = await openCustomer()
    const r = dataRows(t)
    expect(r[0].textContent).toContain('等 2 小時')
    expect(r[0].textContent).not.toContain('Wait PT2H')
    expect(r[1].textContent).toContain('發送訊息：歡迎信')
    expect(r[2].textContent).toContain('旅程結束')
    expect(bodyText()).not.toMatch(/WaitDelayNode|SendMessageNode|EndNode/)
  })

  it('旅程進度列表：同樣的白話說法', async () => {
    backend(routes())
    start(ROUTES.browse)
    const t = await screen.findByRole('table')
    const r = dataRows(t)
    expect(r[0].textContent).toContain('等 2 小時')
    expect(r[0].textContent).not.toContain('Wait PT2H')
    expect(r[1].textContent).toContain('發送訊息：歡迎信')
    expect(r[2].textContent).toContain('旅程結束')
  })
})

describe('#148 文案', () => {
  it('客戶搜尋頁與客戶頁不顯示 D-編號', async () => {
    backend(routes())
    start('/')
    await gotoNav('客戶')
    await screen.findByLabelText('搜尋客戶')
    expect(bodyText()).not.toMatch(/D-\d{3}/)
    await userEvent.type(screen.getByLabelText('搜尋客戶'), PID)
    await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
    await screen.findByRole('table', { name: /參加過/ })
    expect(bodyText()).not.toMatch(/D-\d{3}/)
  })

  it('旅程進度列表沒有 D-編號', async () => {
    backend(routes())
    start(ROUTES.browse)
    await screen.findByRole('table')
    expect(bodyText()).not.toMatch(/D-\d{3}/)
  })

  it('客戶搜尋說明不重複「完全比對」', async () => {
    backend(routes())
    start('/')
    await gotoNav('客戶')
    await screen.findByLabelText('搜尋客戶')
    expect((bodyText().match(/完全比對/g) ?? []).length).toBeLessThanOrEqual(1)
  })

  it('查無結果後送出空白：只剩「請輸入要搜尋的內容」，舊的「找不到」消失', async () => {
    backend({ ...routes(), 'GET /api/v1/profiles/search': () => json({ items: [] }) })
    start('/')
    await gotoNav('客戶')
    await userEvent.type(await screen.findByLabelText('搜尋客戶'), 'zzz')
    await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
    await screen.findByText('找不到符合的客戶')
    await userEvent.clear(screen.getByLabelText('搜尋客戶'))
    await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
    await screen.findByText('請輸入要搜尋的內容。')
    expect(screen.queryByText('找不到符合的客戶')).toBeNull()
    expect(bodyText()).not.toContain('查無')
  })
})

describe('#188 搜尋列', () => {
  it('輸入框與搜尋按鈕在同一容器、按鈕緊接輸入框之後', async () => {
    backend(routes())
    start('/')
    await gotoNav('客戶')
    const input = await screen.findByLabelText('搜尋客戶')
    const btn = screen.getByRole('button', { name: '搜尋' })
    expect(btn.parentElement).toBe(input.parentElement)
    expect(input.nextElementSibling).toBe(btn)
  })

  it('app.css：輸入框與按鈕高 48px；兩者之間沒有間距', () => {
    const all = rulesFor(/search-(big|row)/)
    expect(all.map((r) => r.body).join('\n')).toMatch(/min-height:\s*48px/)
    const row = rulesFor(/\.search-row/)
    expect(row.length).toBeGreaterThan(0)
    const rowGaps = rulesFor(/\.search-row\s*$/).flatMap((r) => gapsOf(r.body))
    for (const g of rowGaps) expect(g).toMatch(/^0(px)?$/)
  })
})

describe('#178 程式聚焦的標題不畫外框', () => {
  it('app.css：[tabindex="-1"]:focus 不畫外框也不畫陰影環', () => {
    const hit = rulesFor(/\[tabindex=["']?-1["']?\]:focus(?!-visible)/)
    expect(hit.length).toBeGreaterThan(0)
    const body = hit.map((r) => r.body).join('\n')
    expect(body).toMatch(/outline:\s*(none|0)/)
    expect(body).toMatch(/box-shadow:\s*none/)
  })

  it('app.css：鍵盤聚焦 :focus-visible 的環照舊', () => {
    const hit = rules().filter((r) => r.sel === ':focus-visible')
    expect(hit.length).toBeGreaterThan(0)
    expect(hit[0].body).toMatch(/box-shadow:\s*var\(--focus-ring\)/)
  })
})
