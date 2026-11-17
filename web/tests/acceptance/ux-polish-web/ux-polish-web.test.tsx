// @vitest-environment jsdom
/**
 * ux-polish-web 驗收（#146 首頁與導覽標示、#150 favicon、#180 列表客戶欄、#187 客戶頁標題）。
 * 依 docs/ui/journey-builder.md「UX issue 對照」、設計稿 ux-list／ux-customer*、journey-engine.md §4.1。
 *
 * 假設（全部列在這裡）：
 *   - 「舊軌跡查詢頁網址」＝ `/`（含 ?q=、?status=）；現在 `/` 本身是旅程列表，帶 q 的舊網址導向 `/customers`（只驗 pathname）。
 *   - 登入後（無 next）與 `/` 顯示旅程列表（h1「旅程」），網址可為 `/` 或 `/journeys`，不假設。
 *   - 客戶標示：profile_name → profile_identifier.value → 「未具名客戶」；第二行小字（`small`）：
 *     有姓名＝識別碼值；僅識別碼＝識別碼類型標籤＋「已遮罩」（若 masked）；都沒有＝「沒有姓名與識別碼」。
 *   - 客戶頁標題 h1 規則同上（姓名來自 attributes.name 且不在 masked_paths；識別碼取 identifiers[0]）。
 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PID, backend, enrollmentSummary, installPolyfills, journeySummary, json, mockApi, page, profileDetail, resetEnv, start,
  teardown,
} from '../journey-customer-ui/fixtures'

vi.setConfig({ testTimeout: 20000 })
beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const navLink = (label: string) =>
  within(screen.getByRole('navigation', { name: '主要導覽' })).getByRole('link', { name: label })

describe('#146 登入後首頁與導覽標示', () => {
  it('登入成功（無 next）進旅程列表，側欄「旅程」亮起、「客戶」不亮', async () => {
    installPolyfills()
    const api = mockApi({ me: null, routes: { 'GET /api/v1/journeys': () => json(page([journeySummary()])) } })
    const u = userEvent.setup()
    start('/login')
    await u.type(await screen.findByLabelText('Email'), 'amy.chen@example.com')
    await u.type(screen.getByLabelText('密碼'), 'correct-horse-battery')
    await u.click(screen.getByRole('button', { name: '登入' }))
    expect(await screen.findByRole('heading', { level: 1, name: '旅程' })).toBeTruthy()
    expect(navLink('旅程').getAttribute('aria-current')).toBe('page')
    expect(navLink('客戶').getAttribute('aria-current')).not.toBe('page')
    expect(api.count('GET', '/api/v1/journeys').length).toBeGreaterThan(0)
    expect(api.calls.some((c) => c.method === 'GET' && c.path === '/api/v1/enrollments')).toBe(false)
  })

  it('已登入進 `/`：旅程列表（h1「旅程」、列出旅程），不是軌跡查詢頁', async () => {
    const api = backend({ 'GET /api/v1/journeys': () => json(page([journeySummary()])) })
    start('/')
    expect(await screen.findByRole('heading', { level: 1, name: '旅程' })).toBeTruthy()
    expect(await screen.findByText('棄單挽回')).toBeTruthy()
    expect(navLink('旅程').getAttribute('aria-current')).toBe('page')
    expect(screen.queryByLabelText(/客戶 ID 或/)).toBeNull()
    expect(api.calls.some((c) => c.method === 'GET' && c.path === '/api/v1/enrollments')).toBe(false)
  })

  it('舊軌跡查詢網址 `/?q=…` 導向「客戶」搜尋，側欄「客戶」亮起', async () => {
    const api = backend()
    start(`/?q=${PID}`)
    await screen.findByRole('heading', { level: 1, name: '客戶' })
    await waitFor(() => expect(window.location.pathname).toBe('/customers'))
    expect(new URLSearchParams(window.location.search).get('q')).toBe(PID) // 導向時保留 q
    expect(navLink('客戶').getAttribute('aria-current')).toBe('page')
    expect(navLink('旅程').getAttribute('aria-current')).not.toBe('page')
    expect(api.calls.some((c) => c.method === 'GET' && c.path === '/api/v1/enrollments')).toBe(false)
  })
})

describe('#150 favicon', () => {
  const html = readFileSync(resolve(__dirname, '../../../index.html'), 'utf-8')
  it('index.html 掛上 SVG favicon，且指向的檔案存在', () => {
    const tag = /<link\b[^>]*rel=["']icon["'][^>]*>/i.exec(html)?.[0]
    expect(tag).toBeTruthy()
    const href = /href=["']([^"']+)["']/.exec(tag!)?.[1]
    expect(href).toMatch(/favicon\.svg$/)
    expect(tag).toMatch(/type=["']image\/svg\+xml["']/)
    expect(existsSync(resolve(__dirname, '../../../public/favicon.svg'))).toBe(true)
  })
})

function rowsFor(items: Record<string, unknown>[]) {
  backend({
    'GET /api/v1/enrollments': () => json(page(items)),
    'GET /api/v1/journeys': () => json(page([journeySummary()])),
  })
  start('/enrollments')
  return screen.findByRole('table')
}
const custCell = (t: HTMLElement) => within(within(t).getAllByRole('row')[1]).getAllByRole('cell')[0]

describe('#180 旅程進度列表客戶欄', () => {
  it('有姓名：第一行姓名（連到客戶頁）、第二行小字為識別碼值；不出現 ID 與「查看客戶」', async () => {
    const t = await rowsFor([enrollmentSummary()])
    const cell = custCell(t)
    const link = within(cell).getByRole('link')
    expect(link.textContent).toBe('陳雅婷')
    expect(link.getAttribute('href')).toContain(`/customers/${PID}`)
    expect(cell.querySelector('small')?.textContent).toContain('M-20418')
    expect(cell.textContent).not.toContain('查看客戶')
    expect(t.textContent).not.toContain(PID)
    expect(t.textContent).not.toContain(PID.slice(-6))
  })

  it('沒有姓名：改用第一個識別碼（後端已遮罩）當連結文字，小字標識別碼類型與「已遮罩」', async () => {
    const t = await rowsFor([enrollmentSummary({
      profile_name: null, profile_identifier: { type: 'EMAIL', value: 'c***@example.com', masked: true },
    })])
    const cell = custCell(t)
    expect(within(cell).getByRole('link').textContent).toBe('c***@example.com')
    const small = cell.querySelector('small')?.textContent ?? ''
    expect(small).toMatch(/email/i)
    expect(small).toContain('已遮罩')
    expect(t.textContent).not.toContain(PID.slice(-6))
  })

  it('姓名與識別碼都沒有：「未具名客戶」＋小字「沒有姓名與識別碼」，仍可連到客戶頁', async () => {
    const t = await rowsFor([enrollmentSummary({ profile_name: null, profile_identifier: null })])
    const cell = custCell(t)
    const link = within(cell).getByRole('link')
    expect(link.textContent).toBe('未具名客戶')
    expect(link.getAttribute('href')).toContain(`/customers/${PID}`)
    expect(cell.querySelector('small')?.textContent).toContain('沒有姓名與識別碼')
    expect(t.textContent).not.toContain(PID.slice(-6))
  })

  it('後端未帶新欄位（舊回應）也不當掉：顯示「未具名客戶」', async () => {
    const e = enrollmentSummary() as Record<string, unknown>
    delete e.profile_name
    delete e.profile_identifier
    const t = await rowsFor([e])
    expect(custCell(t).textContent).toContain('未具名客戶')
  })

  it('客戶欄只有一個連結（第一行）', async () => {
    const t = await rowsFor([enrollmentSummary()])
    expect(within(custCell(t)).getAllByRole('link')).toHaveLength(1)
  })
})

describe('#187 客戶頁標題', () => {
  function openCustomer(detail: Record<string, unknown>) {
    backend({
      [`GET /api/v1/profiles/${PID}`]: () => json(detail),
      [`GET /api/v1/profiles/${PID}/enrollments`]: () => json(page([])),
    })
    start(`/customers/${PID}`)
    return screen.findByRole('heading', { level: 1, name: (n) => n !== '載入中…' })
  }

  it('有姓名（attributes.name、未遮罩）：標題是姓名', async () => {
    const h = await openCustomer(profileDetail({ attributes: { name: '陳雅婷', tier: 'regular' } }))
    expect(h.textContent).toBe('陳雅婷')
  })

  it('沒有姓名：標題是第一個識別碼（後端已遮罩的值），不是「客戶 ＋ ID」', async () => {
    const h = await openCustomer(profileDetail({ attributes: { tier: 'regular' } }))
    expect(h.textContent).toBe('a***@example.com')
    expect(h.textContent).not.toContain(PID.slice(-6))
  })

  it('姓名被遮罩（name 在 masked_paths）：退回識別碼，不以 *** 當標題', async () => {
    const h = await openCustomer(profileDetail({
      attributes: { name: '***' }, masked_paths: ['attributes.name', 'identifiers[0].value'],
    }))
    expect(h.textContent).toBe('a***@example.com')
  })

  it('姓名與識別碼都沒有：標題「未具名客戶」，標題不含 ID', async () => {
    const h = await openCustomer(profileDetail({ identifiers: [], attributes: {}, masked_paths: [] }))
    expect(h.textContent).toBe('未具名客戶')
    expect(h.textContent).not.toContain(PID.slice(-6))
  })
})
