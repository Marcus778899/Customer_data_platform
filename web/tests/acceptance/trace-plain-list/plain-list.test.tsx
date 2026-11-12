// @vitest-environment jsdom
/**
 * trace-plain-list 驗收測試（qa 撰寫，worker 不可改）。對照 docs/ui/enrollment-trace.md「清單頁」、
 * plain.html#after-list、D-079（稱「旅程進度」）、D-080（ID 不當欄位）。fixtures 沿用 journey-customer-ui/fixtures.tsx。
 *
 * 規格沒寫死、因此不測的部分（由 worker 依設計稿決定）：
 *   - 「客戶」欄的顯示文字（API 摘要只有 profile_id，沒有姓名）與連結位置；
 *   - WAITING 的「下一步」文字（摘要沒有 wake_at）；
 *   - 列表不含時間欄，故 formatPlainTime 只在客戶頁驗證。
 * 介面假設：每列有一個連到 /enrollments/<enrollment_id> 的連結；客戶頁連結名稱含旅程名。
 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  EID, PID, ROUTES, backend, bodyText, enrollmentSummary, gotoNav, journeySummary, json, page, profileDetail, profileSummary,
  resetEnv, start, teardown,
} from '../journey-customer-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown(); vi.useRealTimers() })

const ENR = '/api/v1/enrollments'
const PENR = `/api/v1/profiles/${PID}/enrollments`
const LONG_PID = '01JCX7K2M4N8P3Q6R9S5T1V0WZ'
const E4N = '01JD3A5B7C9D1E3F5G7H9J2K4N'
const E4P = '01JD3A5B7C9D1E3F5G7H9J2K4P'

const rowsData = () => [
  enrollmentSummary({ enrollment_id: EID, profile_id: LONG_PID }),
  enrollmentSummary({
    enrollment_id: E4N, journey_name: '新會員歡迎', status: 'COMPLETED',
    current_node_id: 'end_1', current_node_type: 'EndNode', current_node_name: '結束',
  }),
  enrollmentSummary({
    enrollment_id: E4P, journey_name: '棄單挽回', status: 'FAILED',
    current_node_id: 'send_1', current_node_type: 'SendMessageNode', current_node_name: '提醒結帳',
  }),
  enrollmentSummary({
    enrollment_id: '01JD3A5B7C9D1E3F5G7H9J2K4Q', journey_name: '新會員歡迎', status: 'ACTIVE',
    current_node_id: 'send_2', current_node_type: 'SendMessageNode', current_node_name: '歡迎信',
  }),
]
const routes = () => ({
  [`GET ${ENR}`]: () => json(page(rowsData())),
  'GET /api/v1/journeys': () => json(page([journeySummary()])),
})
const dataRows = (t: HTMLElement) => within(t).getAllByRole('row').slice(1)
const cell = (row: HTMLElement, i: number) => within(row).getAllByRole('cell')[i].textContent ?? ''
const HEADERS = ['客戶', '旅程', '狀態', '目前在哪一步', '下一步']
const noTech = (text: string) => {
  expect(text).not.toMatch(/enrollment/i)
  for (const t of ['WaitDelayNode', 'SendMessageNode', 'EndNode', 'WAITING', 'COMPLETED', 'FAILED']) expect(text).not.toContain(t)
}
const optionLabels = (sel: HTMLElement) => [...(sel as HTMLSelectElement).options].map((o) => o.textContent?.trim() ?? '')

describe('旅程進度列表（瀏覽頁 /enrollments）', () => {
  it('頁名「旅程進度」（h1 與麵包屑），畫面沒有 Enrollment 字樣', async () => {
    backend(routes())
    start(ROUTES.browse)
    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('旅程進度')
    expect(screen.getByRole('navigation', { name: '麵包屑' }).textContent).toContain('旅程進度')
    await screen.findByRole('table')
    expect(bodyText()).not.toMatch(/enrollment/i)
  })

  it('欄位＝客戶、旅程、狀態、目前在哪一步、下一步；ID 與節點型別不出現在畫面', async () => {
    backend(routes())
    start(ROUTES.browse)
    const table = await screen.findByRole('table')
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent?.trim())).toEqual(HEADERS)
    const text = bodyText()
    noTech(text)
    for (const id of [EID, LONG_PID, E4N]) expect(text).not.toContain(id)
  })

  it('狀態與目前步驟用白話：等待中／已走完／發生錯誤／進行中；步驟＝等 3 天／旅程結束／發送訊息：名稱', async () => {
    backend(routes())
    start(ROUTES.browse)
    const r = dataRows(await screen.findByRole('table'))
    expect(r).toHaveLength(4)
    expect(cell(r[0], 1)).toContain('棄單挽回')
    expect(cell(r[0], 2)).toContain('等待中')
    expect(cell(r[0], 3)).toContain('等 3 天')
    expect(cell(r[1], 2)).toContain('已走完')
    expect(cell(r[1], 3)).toContain('旅程結束')
    expect(cell(r[2], 2)).toContain('發生錯誤')
    expect(cell(r[2], 3)).toContain('發送訊息：提醒結帳')
    expect(cell(r[3], 2)).toContain('進行中')
    expect(cell(r[3], 3)).toContain('發送訊息：歡迎信')
  })

  it('「下一步」：已走完＝「—」、發生錯誤＝「需要技術人員處理」、進行中＝「處理中」', async () => {
    backend(routes())
    start(ROUTES.browse)
    const r = dataRows(await screen.findByRole('table'))
    expect(cell(r[1], 4).trim()).toBe('—')
    expect(cell(r[2], 4).trim()).toBe('需要技術人員處理')
    expect(cell(r[3], 4).trim()).toBe('處理中')
  })

  it('每列有連到該筆旅程進度詳情的連結（網址仍含 ID）', async () => {
    backend(routes())
    start(ROUTES.browse)
    const r = dataRows(await screen.findByRole('table'))
    for (const [i, id] of [[0, EID], [2, E4P]] as const) {
      const hrefs = within(r[i]).getAllByRole('link').map((a) => a.getAttribute('href'))
      expect(hrefs.some((h) => h?.endsWith(`/enrollments/${id}`))).toBe(true)
    }
  })

  it('狀態篩選選項為白話（不附英文代碼），送出的仍是 API 代碼', async () => {
    const api = backend(routes())
    start(ROUTES.browse)
    await screen.findByRole('table')
    const sel = screen.getByLabelText('狀態')
    const labels = optionLabels(sel)
    expect(labels).toContain('等待中')
    expect(labels).toContain('發生錯誤')
    expect(labels.some((l) => /[A-Z]{4,}/.test(l))).toBe(false)
    await userEvent.selectOptions(sel, 'FAILED')
    await userEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() =>
      expect(api.calls.some((c) => c.path === ENR && new URLSearchParams(c.search).get('status') === 'FAILED')).toBe(true))
  })

  it('空結果與篩選區不出現 Enrollment 字樣', async () => {
    backend({ ...routes(), [`GET ${ENR}`]: () => json(page([])) })
    start(ROUTES.browse)
    await waitFor(() => expect(bodyText()).toMatch(/找不到|沒有/))
    expect(bodyText()).not.toMatch(/enrollment/i)
  })
})

describe('旅程進度查詢頁（/）', () => {
  it('頁名「旅程進度」、查詢框標「客戶 ID 或進度編號」、提示與瀏覽連結不出現 Enrollment', async () => {
    backend(routes())
    start('/')
    expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe('旅程進度')
    expect(screen.getByLabelText('客戶 ID 或進度編號')).toBeTruthy()
    expect(screen.queryByLabelText(/Enrollment/)).toBeNull()
    expect(bodyText()).toContain('輸入客戶 ID 或進度編號開始查詢')
    expect(bodyText()).not.toMatch(/enrollment/i)
  })

  it('查詢結果：欄位照規格、白話狀態與步驟、不顯示 ID 與節點型別；請求仍帶 q', async () => {
    const api = backend(routes())
    start('/')
    await userEvent.type(await screen.findByLabelText('客戶 ID 或進度編號'), `${LONG_PID}{Enter}`)
    const table = await screen.findByRole('table')
    expect(api.calls.some((c) => c.path === ENR && new URLSearchParams(c.search).get('q') === LONG_PID)).toBe(true)
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent?.trim())).toEqual(HEADERS)
    const r = dataRows(table)
    expect(r[0].textContent).toContain('等待中')
    expect(r[0].textContent).toContain('等 3 天')
    expect(r[1].textContent).toContain('已走完')
    expect(r[2].textContent).toContain('發送訊息：提醒結帳')
    const text = bodyText()
    noTech(text)
    expect(text).not.toContain(EID)
  })

  it('查無資料說明與狀態選項用白話（不附英文代碼），沒有 Enrollment 字樣', async () => {
    backend({ ...routes(), [`GET ${ENR}`]: () => json(page([])) })
    start('/?q=zzz')
    await waitFor(() => expect(bodyText()).toContain('找不到'))
    expect(optionLabels(screen.getByLabelText('狀態')).some((l) => /[A-Z]{4,}/.test(l))).toBe(false)
    expect(bodyText()).not.toMatch(/enrollment/i)
  })
})

describe('客戶頁「參加過的旅程」', () => {
  async function openCustomer() {
    backend({
      'GET /api/v1/profiles/search': () => json({ items: [profileSummary({ profile_id: PID, matched_by: 'PROFILE_ID' })] }),
      [`GET /api/v1/profiles/${PID}`]: () => json(profileDetail()),
      [`GET ${PENR}`]: () =>
        json(page([
          enrollmentSummary(),
          enrollmentSummary({
            enrollment_id: 'e2', journey_name: '新會員歡迎', status: 'COMPLETED', current_node_type: 'EndNode', current_node_name: '結束',
            enrolled_at: '2026-09-02T06:30:00Z', terminated_at: '2026-09-04T06:31:00Z',
          }),
        ])),
    })
    start('/')
    await gotoNav('客戶')
    await userEvent.type(await screen.findByLabelText('搜尋客戶'), PID)
    await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
  }

  it('區塊標題「參加過的旅程」、欄位無 ID、畫面沒有 Enrollment 字樣與節點型別', async () => {
    await openCustomer()
    await screen.findByRole('heading', { name: '參加過的旅程' })
    const table = await screen.findByRole('table', { name: /參加過/ })
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent?.trim()))
      .toEqual(['旅程', '狀態', '進入與結束時間'])
    expect(table.textContent).not.toMatch(/enrollment/i)
    expect(table.textContent).not.toContain('EndNode')
    expect(table.textContent).not.toContain('WaitDelayNode')
    expect(table.textContent).not.toContain(EID)
    expect(bodyText()).not.toMatch(/enrollment/i)
  })

  it('時間用 formatPlainTime：同一格「進入 10/18（日）10:00」「結束 9/4（五）14:31」；未結束寫「尚未結束」', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-21T06:00:00Z'))
    await openCustomer()
    const r = dataRows(await screen.findByRole('table', { name: /參加過/ }))
    const t0 = cell(r[0], 2)
    expect(t0).toContain('進入')
    expect(t0).toContain('10/18（日）10:00')
    expect(t0).toContain('尚未結束')
    expect(t0).not.toContain('2026-10-18')
    const t1 = cell(r[1], 2)
    expect(t1).toContain('進入')
    expect(t1).toContain('9/2（三）14:30')
    expect(t1).toContain('結束')
    expect(t1).toContain('9/4（五）14:31')
    expect(t1).not.toContain('尚未結束')
    expect(t1).not.toContain('2026-09-04')
  })

  it('旅程名稱連結仍連到旅程進度詳情', async () => {
    await openCustomer()
    const r = dataRows(await screen.findByRole('table', { name: /參加過/ }))
    const link = within(r[0]).getByRole('link', { name: /棄單挽回/ })
    expect(link.getAttribute('href')).toMatch(/\/enrollments\//)
  })
})
