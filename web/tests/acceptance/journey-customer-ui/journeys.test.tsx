// @vitest-environment jsdom
/** journey-customer-ui：旅程列表（唯讀）與旅程頁（唯讀，D-062）。假設見 fixtures.tsx。 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  JID, backend, bodyText, deferred, gotoNav, journeyDetail, journeySummary, journeyVersion, json, page, params,
  problem, problemWithId, resetEnv, start, teardown,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const list = (items: unknown[], next: string | null = null) => () => json(page(items, next))

async function openList(routes: Parameters<typeof backend>[0]) {
  const api = backend({ 'GET /api/v1/journeys': list([journeySummary()]), ...routes })
  start('/')
  await gotoNav('旅程')
  return api
}

describe('旅程列表', () => {
  it('欄位依 JourneySummary：名稱、狀態、版本、最後更新、最近發佈；不顯示人數、優先度、更新者；v0.2.0 無審核欄與新增鈕', async () => {
    await openList({
      'GET /api/v1/journeys': list([
        journeySummary(),
        journeySummary({ journey_id: 'j2', name: '新會員歡迎', latest_version: 7, updated_at: '2026-10-15T09:40:00Z', published_at: '2026-10-14T09:40:00Z' }),
        journeySummary({ journey_id: 'j3', name: '雙 11 預熱', status: 'DRAFT', latest_version: null, published_at: null, has_draft: true }),
        journeySummary({ journey_id: 'j4', name: '舊版歡迎信', status: 'ABORTED', latest_version: 5 }),
      ]),
    })
    await screen.findByRole('heading', { level: 1, name: '旅程' })
    const table = await screen.findByRole('table')
    const heads = within(table).getAllByRole('columnheader').map((h) => h.textContent?.trim())
    expect(heads).toEqual(['名稱', '狀態', '版本', '最後更新', '最近發佈'])
    const row = (name: string) => within(table).getByRole('link', { name }).closest('tr')!
    const r1 = within(row('棄單挽回'))
    expect(r1.getByText('進行中')).toBeTruthy()
    expect(r1.getByText('v3')).toBeTruthy()
    // 2026-10-18T01:12:00Z → UTC+8
    expect(row('棄單挽回').textContent).toContain('2026-10-18 09:12')
    expect(row('新會員歡迎').textContent).toContain('v7')
    expect(within(row('雙 11 預熱')).getByText('草稿')).toBeTruthy()
    expect(within(row('舊版歡迎信')).getByText('已停止')).toBeTruthy()
    // D-050：不顯示人數；JourneySummary 以外的欄位不出現
    expect(bodyText()).not.toMatch(/人數|客戶數|優先度|更新者/)
    expect(screen.queryByRole('button', { name: /新增旅程|建立/ })).toBeNull()
    expect(screen.queryByRole('columnheader', { name: /審核/ })).toBeNull()
  })

  it('載入中顯示骨架（role=status「載入中」），完成後消失', async () => {
    const d = deferred<Response>()
    backend({ 'GET /api/v1/journeys': () => d.promise })
    start('/')
    await gotoNav('旅程')
    const status = await screen.findByRole('status')
    expect(status.textContent).toContain('載入中')
    d.resolve(json(page([journeySummary()])))
    await screen.findByRole('link', { name: '棄單挽回' })
    expect(screen.queryByText('載入中')).toBeNull()
  })

  it('空：「還沒有旅程」並說明由團隊協助建立', async () => {
    await openList({ 'GET /api/v1/journeys': list([]) })
    await screen.findByRole('heading', { name: '還沒有旅程' })
    expect(bodyText()).toContain('旅程由團隊協助建立並發佈後會出現在這裡')
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('5xx：紅色提示（role=alert）附 request_id 與「重試」，重試後顯示列表', async () => {
    let fail = true
    await openList({
      'GET /api/v1/journeys': () => (fail ? problemWithId(500, 'INTERNAL_ERROR', 'req_5c90') : json(page([journeySummary()]))),
    })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('req_5c90')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('link', { name: '棄單挽回' })
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('cursor 載入更多：帶 cursor 取下一頁、追加列、沒有下一頁時按鈕消失', async () => {
    const api = await openList({
      'GET /api/v1/journeys': (c) =>
        params(c).get('cursor') === 'c1'
          ? json(page([journeySummary({ journey_id: 'j2', name: '第二頁旅程' })]))
          : json(page([journeySummary()], 'c1')),
    })
    await screen.findByRole('link', { name: '棄單挽回' })
    await userEvent.click(await screen.findByRole('button', { name: '載入更多' }))
    await screen.findByRole('link', { name: '第二頁旅程' })
    expect(screen.getByRole('link', { name: '棄單挽回' })).toBeTruthy()
    expect(api.calls.some((c) => c.path === '/api/v1/journeys' && params(c).get('cursor') === 'c1')).toBe(true)
    expect(screen.queryByRole('button', { name: '載入更多' })).toBeNull()
  })

  it('搜尋名稱與狀態篩選送 q、status；無結果顯示「沒有符合條件的旅程」與「清除搜尋」', async () => {
    const api = await openList({
      'GET /api/v1/journeys': (c) => {
        const p = params(c)
        return json(page(p.get('q') || p.get('status') ? [] : [journeySummary()]))
      },
    })
    await screen.findByRole('link', { name: '棄單挽回' })
    await userEvent.type(screen.getByLabelText('搜尋旅程名稱'), '雙 11')
    await waitFor(() =>
      expect(api.calls.some((c) => c.path === '/api/v1/journeys' && params(c).get('q') === '雙 11')).toBe(true))
    await screen.findByRole('heading', { name: '沒有符合條件的旅程' })
    await userEvent.click(screen.getByRole('button', { name: '清除搜尋' }))
    await screen.findByRole('link', { name: '棄單挽回' })
    await userEvent.click(screen.getByRole('button', { name: '進行中' }))
    await waitFor(() =>
      expect(api.calls.some((c) => c.path === '/api/v1/journeys' && params(c).get('status') === 'ACTIVE')).toBe(true))
    expect(screen.getByRole('button', { name: '進行中' }).getAttribute('aria-pressed')).toBe('true')
  })
})

describe('旅程頁（唯讀）', () => {
  const detailRoutes = (over: Parameters<typeof backend>[0] = {}) => ({
    [`GET /api/v1/journeys/${JID}`]: () => json(journeyDetail()),
    [`GET /api/v1/journeys/${JID}/versions/3`]: () => json(journeyVersion(3)),
    [`GET /api/v1/journeys/${JID}/versions/2`]: () => json(journeyVersion(2, '等 2 天')),
    ...over,
  })
  async function openDetail(over: Parameters<typeof backend>[0] = {}) {
    const api = await openList(detailRoutes(over))
    await userEvent.click(await screen.findByRole('link', { name: '棄單挽回' }))
    return api
  }
  const nodeButton = (name: RegExp) => screen.findByRole('button', { name, hidden: true })

  it('標頭（名稱、狀態、最新版本、最近發佈、最後更新、說明）＋版本下拉＋節點圖；預設最新版', async () => {
    const api = await openDetail()
    const h1 = await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    expect(h1.textContent).toContain('進行中')
    await nodeButton(/加入購物車/)
    const text = bodyText()
    expect(text).toContain('最新版本 v3')
    expect(text).toContain('2026-10-18 09:12')
    expect(text).toContain('加入購物車未結帳的挽回流程')
    const select = screen.getByLabelText('版本') as HTMLSelectElement
    expect([...select.options].map((o) => o.textContent)).toEqual([
      expect.stringContaining('v3（最新）'), expect.stringContaining('v2'), expect.stringContaining('v1'),
    ])
    expect(select.value).toBe('3')
    // 預設取最新版本的定義
    expect(api.calls.some((c) => c.path === `/api/v1/journeys/${JID}/versions/3`)).toBe(true)
    expect(screen.queryByText(/不是最新版本/)).toBeNull()
  })

  it('節點圖：每個節點是 button[aria-pressed]，圖為 role=group，另有依序的文字版節點清單；唯讀無編輯／停止／審核鈕；不疊軌跡', async () => {
    await openDetail()
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    for (const name of [/開始/, /加入購物車/, /等 3 天/, /提醒信/, /結束/]) {
      const b = await nodeButton(name)
      expect(b.hasAttribute('aria-pressed')).toBe(true)
    }
    expect(document.querySelector('[role="group"]')).toBeTruthy()
    const summary = [...document.querySelectorAll('summary')].find((s) => s.textContent?.includes('節點清單'))
    expect(summary).toBeTruthy()
    const items = [...summary!.closest('details')!.querySelectorAll('ol li')].map((li) => li.textContent ?? '').join(' ')
    const order = ['開始', '加入購物車', '等 3 天', '提醒信', '結束'].map((n) => items.indexOf(n))
    expect(order.every((i) => i >= 0)).toBe(true)
    expect([...order].sort((a, b) => a - b)).toEqual(order)
    expect(screen.queryByRole('button', { name: /編輯|停止|送審|核准|退回|發佈/ })).toBeNull()
    expect(bodyText()).not.toMatch(/已經過|目前位置/)
    expect(bodyText()).toContain('窄螢幕：圖可左右捲動')
  })

  it('layout 為 null 或缺節點座標時仍畫出全部節點（dagre 自動排版）', async () => {
    const partial = journeyVersion(3, '等 3 天', { layout: { nodes: { start: { x: 0, y: 0 } } } })
    await openDetail({ [`GET /api/v1/journeys/${JID}/versions/3`]: () => json(partial) })
    for (const name of [/開始/, /加入購物車/, /等 3 天/, /提醒信/, /結束/]) await nodeButton(name)
  })

  it('切到舊版：重新取該版定義、顯示「你正在看 v2，不是最新版本」與最新版 v3；「回到最新版本」切回', async () => {
    const api = await openDetail()
    await nodeButton(/等 3 天/)
    await userEvent.selectOptions(screen.getByLabelText('版本'), '2')
    await nodeButton(/等 2 天/)
    expect(api.calls.some((c) => c.path === `/api/v1/journeys/${JID}/versions/2`)).toBe(true)
    const notice = await screen.findByText(/你正在看 v2，不是最新版本/)
    expect(notice.textContent).toContain('v3')
    expect(screen.queryByRole('button', { name: /等 3 天/, hidden: true })).toBeNull()
    await userEvent.click(screen.getByText('回到最新版本'))
    await nodeButton(/等 3 天/)
    expect(screen.queryByText(/不是最新版本/)).toBeNull()
    expect((screen.getByLabelText('版本') as HTMLSelectElement).value).toBe('3')
  })

  it('404 JOURNEY_NOT_FOUND：「找不到這個旅程」＋回旅程列表連結（role=alert）', async () => {
    await openDetail({ [`GET /api/v1/journeys/${JID}`]: () => problem(404, 'JOURNEY_NOT_FOUND') })
    const h = await screen.findByRole('heading', { name: '找不到這個旅程' })
    expect(h.closest('[role="alert"]')).toBeTruthy()
    expect(screen.getByRole('link', { name: '回旅程列表' })).toBeTruthy()
  })

  it('5xx：附 request_id 與重試，重試後顯示旅程', async () => {
    let fail = true
    await openDetail({
      [`GET /api/v1/journeys/${JID}`]: () => (fail ? problemWithId(500, 'INTERNAL_ERROR', 'req_a3b7') : json(journeyDetail())),
    })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('req_a3b7')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
  })

  it('載入中顯示骨架（role=status）', async () => {
    const d = deferred<Response>()
    await openDetail({ [`GET /api/v1/journeys/${JID}`]: () => d.promise })
    const status = await screen.findByRole('status')
    expect(status.textContent).toContain('載入中')
    d.resolve(json(journeyDetail()))
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
  })
})
