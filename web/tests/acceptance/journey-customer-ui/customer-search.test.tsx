// @vitest-environment jsdom
/** journey-customer-ui：客戶搜尋（D-048）。假設見 fixtures.tsx。 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  PID, backend, bodyText, deferred, gotoNav, json, params, problem, problemWithId, profileDetail, profileSummary,
  resetEnv, start, teardown, page,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const SEARCH = '/api/v1/profiles/search'
const searches = (api: ReturnType<typeof backend>) => api.calls.filter((c) => c.path === SEARCH)
const items = (list: unknown[]) => () => json({ items: list })

async function openSearch(routes: Parameters<typeof backend>[0]) {
  const api = backend({
    [`GET /api/v1/profiles/${PID}`]: () => json(profileDetail()),
    [`GET /api/v1/profiles/${PID}/enrollments`]: () => json(page([])),
    ...routes,
  })
  start('/')
  await gotoNav('客戶')
  await screen.findByRole('heading', { level: 1, name: '客戶' })
  return api
}
async function search(q: string) {
  const box = screen.getByLabelText('搜尋客戶')
  await userEvent.clear(box)
  if (q) await userEvent.type(box, q)
  await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
}
const two = () => [
  profileSummary({ matched_by: 'MEMBER_ID' }),
  profileSummary({
    profile_id: '01JCX7K2M4N8P3Q6R9S5T7H9KMN', matched_by: 'PROFILE_ID', updated_at: '2026-10-30T01:12:00Z',
    identifiers: [{ type: 'PHONE', value: '***', masked: true }],
  }),
]

describe('客戶搜尋', () => {
  it('初始：說明可輸入的內容（email、手機、外部 ID、客戶 ID）與完全比對提示，且不送出搜尋', async () => {
    const api = await openSearch({ [`GET ${SEARCH}`]: items([]) })
    const text = bodyText()
    expect(text).toContain('可以輸入什麼')
    expect(text).toContain('完全比對')
    for (const w of ['email', '手機', '外部 ID', '客戶 ID']) expect(text).toContain(w)
    expect(searches(api)).toHaveLength(0)
  })

  it('單筆結果直接進客戶頁，並顯示「搜尋『…』比對到這位客戶（比對方式：email）」；請求 q 為輸入值', async () => {
    const api = await openSearch({ [`GET ${SEARCH}`]: items([profileSummary()]) })
    await search('amy@example.com')
    const note = await screen.findByText(/比對到這位客戶/)
    expect(note.textContent).toContain('amy@example.com')
    expect(note.textContent).toContain('比對方式：email')
    expect(params(searches(api)[0]).get('q')).toBe('amy@example.com')
    // 進到客戶頁：有基本資料區，沒有結果表
    await screen.findByRole('heading', { name: '基本資料' })
    expect(screen.queryByRole('table', { name: /找到/ })).toBeNull()
    await waitFor(() => expect(document.activeElement?.tagName).toBe('H1'))
  })

  it('多筆結果：表格（比對方式、識別碼、客戶 ID、最近更新）、遮罩徽章、長 ID 只顯示頭尾，連結進客戶頁', async () => {
    await openSearch({ [`GET ${SEARCH}`]: items(two()) })
    await search('M-20418')
    const table = await screen.findByRole('table')
    expect(table.querySelector('caption')!.textContent).toContain('找到 2 位客戶')
    expect(table.querySelector('caption')!.textContent).toContain('最多顯示 20 筆')
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent?.trim()))
      .toEqual(['比對方式', '識別碼', '客戶 ID', '最近更新'])
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('會員編號') // MEMBER_ID
    expect(rows[0].textContent).toContain('M-20418')
    expect(rows[0].textContent).toContain('a***@example.com')
    expect(within(rows[0]).getByText('已遮罩')).toBeTruthy()
    expect(rows[1].textContent).toContain('客戶 ID') // PROFILE_ID
    expect(within(rows[1]).getByText('已遮罩')).toBeTruthy()
    expect(rows[1].textContent).toContain('2026-10-30 09:12')
    const link = within(rows[0]).getByRole('link')
    expect(link.textContent).toContain('…')
    expect(link.textContent).not.toBe(PID)
    // 完成後焦點到結果標題
    await waitFor(() => expect(['H1', 'H2', 'H3']).toContain(document.activeElement?.tagName))
    await userEvent.click(link)
    await screen.findByRole('heading', { name: '基本資料' })
  })

  it('最多 20 筆：後端回 20 筆就全列出', async () => {
    const many = Array.from({ length: 20 }, (_, i) =>
      profileSummary({ profile_id: `01JCX7K2M4N8P3Q6R9S5T${String(i).padStart(5, '0')}`, matched_by: 'MEMBER_ID' }))
    await openSearch({ [`GET ${SEARCH}`]: items(many) })
    await search('M-1')
    const table = await screen.findByRole('table')
    expect(within(table).getAllByRole('row')).toHaveLength(21)
  })

  it('網址同步 ?q=：送出後網址帶 q；以該網址重新載入會自動搜尋並顯示同樣結果', async () => {
    const api = await openSearch({ [`GET ${SEARCH}`]: items(two()) })
    await search('M-20418')
    await screen.findByRole('table')
    expect(new URLSearchParams(window.location.search).get('q')).toBe('M-20418')
    const url = window.location.pathname + window.location.search
    cleanup()
    const before = searches(api).length
    start(url)
    await screen.findByRole('table')
    expect(searches(api).length).toBeGreaterThan(before)
    expect(params(searches(api).at(-1)!).get('q')).toBe('M-20418')
    expect((screen.getByLabelText('搜尋客戶') as HTMLInputElement).value).toBe('M-20418')
  })

  it('查無（200、items 為空）：「找不到符合的客戶」並列出可能原因', async () => {
    await openSearch({ [`GET ${SEARCH}`]: items([]) })
    await search('nobody@example.com')
    await screen.findByRole('heading', { name: '找不到符合的客戶' })
    const text = bodyText()
    expect(text).toContain('可能原因')
    expect(text).toContain('輸入內容不完整或有錯字')
    expect(text).toContain('客戶尚未送過任何事件')
    expect(text).toContain('識別碼尚未綁定到客戶')
  })

  it('空字串：「請輸入要搜尋的內容。」顯示在欄位旁，aria-invalid 並以 aria-describedby 關聯', async () => {
    await openSearch({ [`GET ${SEARCH}`]: () => problem(400, 'VALIDATION_FAILED') })
    await search('')
    const msg = await screen.findByText('請輸入要搜尋的內容。')
    const box = screen.getByLabelText('搜尋客戶')
    expect(box.getAttribute('aria-invalid')).toBe('true')
    const ids = (box.getAttribute('aria-describedby') ?? '').split(/\s+/)
    expect(ids).toContain(msg.id)
    expect(bodyText()).not.toContain('搜尋失敗')
  })

  it('搜尋中：按鈕「搜尋中…」並有載入狀態（role=status）', async () => {
    const d = deferred<Response>()
    await openSearch({ [`GET ${SEARCH}`]: () => d.promise })
    await search('abc')
    expect(await screen.findByRole('button', { name: '搜尋中…' })).toBeTruthy()
    expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('載入中'))).toBe(true)
    d.resolve(json({ items: [] }))
    await screen.findByRole('heading', { name: '找不到符合的客戶' })
  })

  it('5xx：role=alert 附 request_id 與重試，重試成功顯示結果', async () => {
    let fail = true
    await openSearch({
      [`GET ${SEARCH}`]: () => (fail ? problemWithId(503, 'SERVICE_UNAVAILABLE', 'req_77d1') : json({ items: two() })),
    })
    await search('abc')
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('搜尋失敗')
    expect(alert.textContent).toContain('req_77d1')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('table')
  })
})
