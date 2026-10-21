// @vitest-environment jsdom
/**
 * journey-customer-ui：最近 Enrollment 瀏覽（D-030；journey-engine §4 `/enrollments`，`q` 省略 = 瀏覽）。
 * 網址 ROUTES.browse（= `/enrollments`，routes.md §1）；「旅程」篩選控制項（label 含「旅程」的 <select>，選項來自 GET /api/v1/journeys）是假設，見 fixtures.tsx。
 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  ROUTES, backend, bodyText, enrollmentSummary, journeySummary, json, page, params, problemWithId, resetEnv, start, teardown,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const ENR = '/api/v1/enrollments'
const rows = () => [
  enrollmentSummary({ enrollment_id: 'e1' }),
  enrollmentSummary({ enrollment_id: 'e2', journey_id: 'j2', journey_name: '新會員歡迎', journey_version: 7, status: 'COMPLETED', current_node_name: '結束', current_node_type: 'EndNode' }),
]
const browseCalls = (api: ReturnType<typeof backend>) => api.calls.filter((c) => c.path === ENR)

function openBrowse(routes: Parameters<typeof backend>[0] = {}) {
  const api = backend({
    [`GET ${ENR}`]: () => json(page(rows())),
    'GET /api/v1/journeys': () => json(page([journeySummary(), journeySummary({ journey_id: 'j2', name: '新會員歡迎' })])),
    ...routes,
  })
  start(ROUTES.browse)
  return api
}

describe('最近 Enrollment 瀏覽', () => {
  it('不輸入 q 即載入最近的 Enrollment（請求不帶 q），列出旅程（版本）、狀態、目前節點、進入時間，新到舊', async () => {
    const api = openBrowse()
    const table = await screen.findByRole('table')
    expect(browseCalls(api).length).toBeGreaterThan(0)
    expect(params(browseCalls(api)[0]).has('q')).toBe(false)
    const r = within(table).getAllByRole('row').slice(1)
    expect(r).toHaveLength(2)
    expect(r[0].textContent).toContain('棄單挽回（v3）')
    expect(r[0].textContent).toContain('等待中')
    expect(r[0].textContent).toContain('等 3 天')
    expect(r[0].textContent).toContain('2026-10-18 10:00')
    expect(r[1].textContent).toContain('新會員歡迎（v7）')
    expect(r[1].textContent).toContain('已完成')
  })

  it('依狀態篩選：送出 status=…，q 仍省略', async () => {
    const api = openBrowse()
    await screen.findByRole('table')
    await userEvent.selectOptions(screen.getByLabelText('狀態'), 'WAITING')
    await userEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(browseCalls(api).some((c) => params(c).get('status') === 'WAITING')).toBe(true))
    const call = browseCalls(api).find((c) => params(c).get('status') === 'WAITING')!
    expect(params(call).has('q')).toBe(false)
  })

  it('依旅程篩選：選項取自旅程列表，送出 journey_id=…；可與狀態併用', async () => {
    const api = openBrowse()
    await screen.findByRole('table')
    const select = await screen.findByLabelText(/^旅程/)
    await waitFor(() => expect([...(select as HTMLSelectElement).options].some((o) => o.textContent?.includes('新會員歡迎'))).toBe(true))
    await userEvent.selectOptions(select, 'j2')
    await userEvent.selectOptions(screen.getByLabelText('狀態'), 'COMPLETED')
    await userEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() =>
      expect(browseCalls(api).some((c) => params(c).get('journey_id') === 'j2' && params(c).get('status') === 'COMPLETED')).toBe(true))
  })

  it('篩選後網址同步 status', async () => {
    openBrowse()
    await screen.findByRole('table')
    await userEvent.selectOptions(screen.getByLabelText('狀態'), 'WAITING')
    await userEvent.click(screen.getByRole('button', { name: '查詢' }))
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('status')).toBe('WAITING'))
    expect(screen.getByRole('link', { name: /棄單挽回（v3）/ })).toBeTruthy()
  })

  it('篩選無結果：顯示查無說明，不是錯誤', async () => {
    const api = openBrowse({ [`GET ${ENR}`]: () => json(page([])) })
    await screen.findByLabelText('狀態')
    await waitFor(() => expect(browseCalls(api).length).toBeGreaterThan(0))
    await waitFor(() => expect(bodyText()).toMatch(/找不到|沒有/))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('cursor 載入更多', async () => {
    const api = openBrowse({
      [`GET ${ENR}`]: (c) =>
        params(c).get('cursor') === 'c2'
          ? json(page([enrollmentSummary({ enrollment_id: 'e9', journey_name: '第二頁旅程' })]))
          : json(page(rows(), 'c2')),
    })
    await screen.findByRole('table')
    await userEvent.click(await screen.findByRole('button', { name: '載入更多' }))
    await screen.findByText(/第二頁旅程/)
    expect(browseCalls(api).some((c) => params(c).get('cursor') === 'c2')).toBe(true)
  })

  it('5xx：alert 附 request_id 與重試', async () => {
    let fail = true
    openBrowse({ [`GET ${ENR}`]: () => (fail ? problemWithId(500, 'INTERNAL_ERROR', 'req_br1') : json(page(rows()))) })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('req_br1')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('table')
  })
})
