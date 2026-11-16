// @vitest-environment jsdom
/** journey-review-ui：列表「待審核」篩選（rv-queue）、審核紀錄時間軸（rv-history）、管理員審核頁（rv-review）。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  API, AMY, REF_AMY, REF_DAMING, REF_WANG, REVIEW_PATH, RID, VIEWER, WANG, action, bodyText, brief, callsTo, cleanup, deferred,
  expectAbsent, expectDisabled, expectEnabled, freezeNow, journeyBackend, journeySummary, json, mustAction, openJourney, page, params,
  problem, rejected, resetEnv, review, screen, start, teardown, unfreeze, userEvent, waitFor, within, draftDef, scenario,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { unfreeze(); cleanup(); teardown() })

const NOW = '2026-11-07T04:00:00Z'
const pendingRow = (id: string, name: string, submittedAt: string, by = REF_AMY, over: Record<string, unknown> = {}) => {
  const r = review({ review_id: `R-${id}`, submitted_by: by, submitted_at: submittedAt })
  return journeySummary({ journey_id: id, name, review_status: 'PENDING', has_draft: true, latest_review: brief(r), updated_at: submittedAt, ...over })
}

function listBackend(me = WANG) {
  const all = [
    journeySummary({ latest_review: null }),
    journeySummary({ journey_id: 'j9', name: '春季檔期', latest_review: null }),
  ]
  const pend = [
    pendingRow('j1', '棄單挽回', '2026-11-07T02:00:00Z'),
    pendingRow('j2', '新會員歡迎', '2026-11-06T04:00:00Z', REF_AMY, { latest_version: 7 }),
    pendingRow('j3', '生日禮提醒', '2026-11-05T04:00:00Z', REF_DAMING, { status: 'DRAFT', latest_version: null, published_at: null }),
  ]
  const rej = [
    journeySummary({
      journey_id: 'j5', name: '會員召回', review_status: 'REJECTED', has_draft: true,
      latest_review: brief(rejected({ review_id: 'R-j5' })),
    }),
  ]
  return journeyBackend({
    me,
    routes: {
      'GET /api/v1/journeys': (c) => {
        const p = params(c)
        const rs = p.get('review_status')
        if (rs === 'PENDING') return json(page(pend))
        if (rs === 'REJECTED') return json(page(rej))
        if (p.get('status') === 'ABORTED') return json(page([journeySummary({ journey_id: 'j7', name: '舊版歡迎信', status: 'ABORTED', latest_review: null })]))
        return json(page(all))
      },
    },
  })
}
const chip = (re: RegExp) => screen.getByRole('button', { name: re })

describe('旅程列表：待審核篩選', () => {
  it('篩選列有「待審核」「已退回」（名稱可帶數量，aria-pressed）；預設「全部」按下', async () => {
    listBackend()
    start('/journeys')
    await screen.findByRole('link', { name: '棄單挽回' })
    expect(chip(/^全部/).getAttribute('aria-pressed')).toBe('true')
    expect(chip(/^待審核/).getAttribute('aria-pressed')).toBe('false')
    expect(chip(/^已退回/).getAttribute('aria-pressed')).toBe('false')
    expect(chip(/^已停止/)).toBeTruthy()
  })

  it('點「待審核」→ GET ?review_status=PENDING；顯示送審者與等待時間（2 小時、1 天、2 天）與審核狀態、草稿版本', async () => {
    freezeNow(NOW)
    const { api } = listBackend()
    start('/journeys')
    await screen.findByRole('link', { name: '棄單挽回' })
    await userEvent.click(chip(/^待審核/))
    await screen.findByRole('link', { name: '新會員歡迎' })
    expect(chip(/^待審核/).getAttribute('aria-pressed')).toBe('true')
    const last = api.calls.filter((c) => c.path === '/api/v1/journeys').at(-1)!
    expect(params(last).get('review_status')).toBe('PENDING')
    const table = screen.getByRole('table')
    const heads = within(table).getAllByRole('columnheader').map((h) => h.textContent?.trim() ?? '')
    expect(heads.some((h) => h.includes('送審者與等待時間'))).toBe(true)
    expect(heads.some((h) => h.includes('審核'))).toBe(true)
    const row = (n: string) => within(table).getByRole('link', { name: n }).closest('tr')!.textContent ?? ''
    expect(row('棄單挽回')).toContain('審核中')
    expect(row('棄單挽回')).toContain('草稿 v4')
    expect(row('棄單挽回')).toContain('陳小美')
    expect(row('棄單挽回')).toContain('等待 2 小時')
    expect(row('新會員歡迎')).toContain('草稿 v8')
    expect(row('新會員歡迎')).toContain('等待 1 天')
    expect(row('生日禮提醒')).toContain('草稿 v1')
    expect(row('生日禮提醒')).toContain('王大明')
    expect(row('生日禮提醒')).toContain('等待 2 天')
    expect(table.querySelector('caption')?.textContent).toContain('共 3 個')
  })

  it('點「已退回」→ review_status=REJECTED，列出被退回的旅程並標示「已退回」', async () => {
    const { api } = listBackend()
    start('/journeys')
    await screen.findByRole('link', { name: '棄單挽回' })
    await userEvent.click(chip(/^已退回/))
    const link = await screen.findByRole('link', { name: '會員召回' })
    expect(link.closest('tr')!.textContent).toContain('已退回')
    expect(params(api.calls.filter((c) => c.path === '/api/v1/journeys').at(-1)!).get('review_status')).toBe('REJECTED')
  })

  it('沒有待審核：「目前沒有待審核的旅程。有人送審後會出現在這裡。」＋「看全部旅程」回到全部', async () => {
    journeyBackend({
      me: WANG,
      routes: {
        'GET /api/v1/journeys': (c) => json(page(params(c).get('review_status') ? [] : [journeySummary({ latest_review: null })])),
      },
    })
    start('/journeys')
    await screen.findByRole('link', { name: '棄單挽回' })
    await userEvent.click(chip(/^待審核/))
    const status = await screen.findByRole('status')
    expect(status.textContent).toContain('目前沒有待審核的旅程。有人送審後會出現在這裡。')
    await userEvent.click(screen.getByRole('button', { name: '看全部旅程' }))
    await screen.findByRole('link', { name: '棄單挽回' })
    expect(chip(/^全部/).getAttribute('aria-pressed')).toBe('true')
  })

  it('檢視者也能用「待審核」篩選（唯讀）', async () => {
    listBackend(VIEWER)
    start('/journeys')
    await screen.findByRole('link', { name: '棄單挽回' })
    await userEvent.click(chip(/^待審核/))
    await screen.findByRole('link', { name: '新會員歡迎' })
  })

  it('5xx：role=alert 附請求編號與「重試」', async () => {
    let fail = true
    journeyBackend({
      me: WANG,
      routes: {
        'GET /api/v1/journeys': (c) =>
          fail && params(c).get('review_status')
            ? json({ type: 'about:blank', title: 'x', status: 500, code: 'INTERNAL', detail: 'd', request_id: 'REQ-Q-500' }, 500)
            : json(page([journeySummary({ latest_review: null })])),
      },
    })
    start('/journeys')
    await screen.findByRole('link', { name: '棄單挽回' })
    await userEvent.click(chip(/^待審核/))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('REQ-Q-500')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })
})

// ---------------------------------------------------------------- 審核紀錄

const hist = (...rs: Record<string, unknown>[]) => rs
const KEY = review({
  review_id: 'R-key', status: 'APPROVED', revision: null, base_version: null, note: null, submitted_by: null,
  submitted_at: '2026-10-19T02:00:00Z', decided_by: null, decided_at: '2026-10-19T02:00:00Z', reason: '管理金鑰建立', version: 3,
})
const APPROVED = review({ review_id: 'R-ok', status: 'APPROVED', decided_by: REF_WANG, decided_at: '2026-11-07T02:48:00Z', reason: '等待時間照建議', version: 4, submitted_at: '2026-11-07T02:20:00Z' })
const REJ = rejected({ review_id: 'R-rej', submitted_at: '2026-11-06T07:02:00Z', decided_at: '2026-11-06T08:30:00Z' })
const WD = review({ review_id: 'R-wd', status: 'WITHDRAWN', decided_by: REF_AMY, decided_at: '2026-11-02T06:10:00Z', reason: '還要補一個條件', submitted_at: '2026-11-02T05:00:00Z', note: null })
const timeline = () =>
  [...document.querySelectorAll('ol')].filter((ol) => !ol.closest('nav') && !ol.getAttribute('aria-label')).flatMap((ol) => [...ol.querySelectorAll(':scope > li')])
const rowText = (li: Element) => (li.textContent ?? '').replace(/\s+/g, ' ')

async function openHistory(o: Parameters<typeof journeyBackend>[0]) {
  const b = await openJourney(o)
  await userEvent.click(await mustAction('審核紀錄'))
  return b
}

describe('審核紀錄', () => {
  it('每筆審核一列、新到舊：核准（核准並發佈 vN＋備註）、退回（原因）、撤回送審（原因）、管理金鑰建立（管理金鑰 建立 v3＋原因）', async () => {
    await openHistory({ me: AMY, kind: 'active', reviews: hist(APPROVED, REJ, WD, KEY) })
    await waitFor(() => expect(timeline().length).toBe(4))
    const rows = timeline().map(rowText)
    expect(rows[0]).toContain('王小華')
    expect(rows[0]).toContain('核准並發佈 v4')
    expect(rows[0]).toContain('等待時間照建議')
    expect(rows[1]).toContain('王小華')
    expect(rows[1]).toContain('退回')
    expect(rows[1]).toContain('等待時間請改為 2 天')
    expect(rows[2]).toContain('陳小美')
    expect(rows[2]).toContain('撤回送審')
    expect(rows[2]).toContain('還要補一個條件')
    expect(rows[3]).toContain('管理金鑰')
    expect(rows[3]).toContain('建立 v3')
    expect(rows[3]).toContain('管理金鑰建立')
    expect(bodyText()).toContain('紀錄不能修改或刪除')
  })

  it('送審中的那筆：操作者是送審者、動作「送審」、說明取 note', async () => {
    await openHistory({ me: AMY, kind: 'pending', submitter: REF_AMY })
    await waitFor(() => expect(timeline().length).toBe(1))
    const r = rowText(timeline()[0])
    expect(r).toContain('陳小美')
    expect(r).toContain('送審')
    expect(r).toContain('新增 VIP 分流與再次提醒')
  })

  it('已停止的旅程：「停止」併成一列（停止者、原因），在最上面（最新）；其餘照常', async () => {
    const stoppedWd = review({ review_id: 'R-stopwd', status: 'WITHDRAWN', decided_by: REF_WANG, decided_at: '2026-11-06T09:05:00Z', reason: '旅程已停止', submitted_at: '2026-11-06T08:40:00Z' })
    await openHistory({ me: WANG, kind: 'aborted', reviews: hist(stoppedWd, REJ, KEY) })
    await waitFor(() => expect(timeline().length).toBe(4))
    const rows = timeline().map(rowText)
    expect(rows[0]).toContain('王小華')
    expect(rows[0]).toContain('停止旅程')
    expect(rows[0]).toContain('活動結束')
    expect(rows.filter((r) => r.includes('停止旅程')).length).toBe(1)
    expect(rows.some((r) => r.includes('管理金鑰') && r.includes('建立 v3'))).toBe(true)
    expect(bodyText()).toContain('已停止')
  })

  it('檢視者可看，沒有任何操作鈕（不能修改或刪除紀錄）', async () => {
    await openHistory({ me: VIEWER, kind: 'active', reviews: hist(APPROVED, KEY) })
    await waitFor(() => expect(timeline().length).toBe(2))
    const buttons = [...document.querySelectorAll('main button, [role="main"] button')].map((b) => (b.textContent ?? '').trim())
    expect(buttons.filter((t) => /刪除|修改|編輯|核准|退回|撤回/.test(t))).toEqual([])
  })

  it('空：「還沒有審核紀錄。送審、核准、退回或停止後，會依時間出現在這裡。」', async () => {
    await openHistory({ me: AMY, kind: 'active', reviews: [] })
    const s = await screen.findByText(/還沒有審核紀錄/)
    expect(s.textContent).toContain('送審、核准、退回或停止後，會依時間出現在這裡。')
    expect(timeline().length).toBe(0)
  })

  it('載入中：role=status「載入審核紀錄中」，完成後消失', async () => {
    const d = deferred<Response>()
    await openHistory({ me: AMY, kind: 'active', routes: { [`GET ${API}/reviews`]: () => d.promise } })
    expect(await screen.findByRole('status', { name: '載入審核紀錄中' })).toBeTruthy()
    d.resolve(json(page(hist(KEY))))
    await waitFor(() => expect(timeline().length).toBe(1))
    expect(screen.queryByRole('status', { name: '載入審核紀錄中' })).toBeNull()
  })

  it('5xx：role=alert「暫時無法載入審核紀錄」附請求編號；重試後顯示', async () => {
    let fail = true
    await openHistory({
      me: AMY, kind: 'active',
      routes: {
        [`GET ${API}/reviews`]: () =>
          fail ? json({ type: 'about:blank', title: 'x', status: 500, code: 'INTERNAL', detail: 'd', request_id: 'REQ-H-500' }, 500) : json(page(hist(KEY))),
      },
    })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('暫時無法載入審核紀錄')
    expect(alert.textContent).toContain('REQ-H-500')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await waitFor(() => expect(timeline().length).toBe(1))
  })

  it('404 JOURNEY_NOT_FOUND：「找不到這個旅程」＋「回旅程列表」', async () => {
    await openHistory({ me: AMY, kind: 'active', routes: { [`GET ${API}/reviews`]: () => problem(404, 'JOURNEY_NOT_FOUND') } })
    expect(await screen.findByText(/找不到這個旅程/)).toBeTruthy()
    expect(screen.getByRole('link', { name: '回旅程列表' })).toBeTruthy()
  })

  it('載入更多：帶 cursor 取下一頁並接在後面；失敗時已載入的保留、提示「更多紀錄載入失敗」與「重試」', async () => {
    let failMore = true
    const { api } = await openHistory({
      me: AMY, kind: 'active',
      routes: {
        [`GET ${API}/reviews`]: (c) => {
          const cur = params(c).get('cursor')
          if (!cur) return json(page(hist(APPROVED), 'c1'))
          if (failMore) return problem(500, 'INTERNAL')
          return json(page(hist(KEY)))
        },
      },
    })
    await waitFor(() => expect(timeline().length).toBe(1))
    await userEvent.click(await screen.findByRole('button', { name: '載入更多' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('更多紀錄載入失敗')
    expect(timeline().length).toBe(1)
    failMore = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await waitFor(() => expect(timeline().length).toBe(2))
    expect(callsTo(api, 'GET', `${API}/reviews`).some((c) => params(c).get('cursor') === 'c1')).toBe(true)
  })
})

// ---------------------------------------------------------------- 管理員審核頁

function reviewBackend(me = WANG, over: Parameters<typeof journeyBackend>[0] = {}) {
  return journeyBackend({ me, kind: 'pending', submitter: REF_AMY, ...over })
}
async function openReview(me = WANG, over: Parameters<typeof journeyBackend>[0] = {}) {
  const b = reviewBackend(me, over)
  start(REVIEW_PATH)
  await screen.findByRole('heading', { level: 1, name: /審核 v4/ })
  return b
}

describe('審核頁', () => {
  it('標頭：「棄單挽回：審核 v4」、送審者與基於版本、送審說明、核准前上線的仍是 v3', async () => {
    await openReview()
    const h1 = screen.getByRole('heading', { level: 1 })
    expect(h1.textContent).toContain('棄單挽回')
    expect(bodyText()).toContain('陳小美')
    expect(bodyText()).toContain('基於 v3')
    expect(bodyText()).toContain('新增 VIP 分流與再次提醒，等待改 24 小時。')
    expect(bodyText()).toContain('核准前上線的仍是 v3')
  })

  it('變更摘要「跟 v3 比，這次改了什麼」：新增 2 個節點、修改 1 個節點、刪除 0 個；明細表標新增／修改', async () => {
    await openReview()
    expect(await screen.findByText(/跟 v3 比，這次改了什麼/)).toBeTruthy()
    expect(bodyText()).toContain('新增 2 個節點')
    expect(bodyText()).toContain('修改 1 個節點')
    expect(bodyText()).toContain('刪除 0 個')
    const table = document.querySelector('table')!
    expect(table.querySelector('caption')?.textContent).toContain('節點變更明細')
    const rows = [...table.querySelectorAll('tbody tr')].map((r) => (r.textContent ?? '').replace(/\s+/g, ' '))
    expect(rows.length).toBe(3)
    expect(rows.find((r) => r.includes('是 VIP？'))).toContain('新增')
    expect(rows.find((r) => r.includes('VIP 專屬關心'))).toContain('新增')
    expect(rows.find((r) => r.includes('等 3 天'))).toContain('修改')
  })

  it('草稿刪掉一個節點 → 「刪除 1 個」；沒有變更的節點不列在明細', async () => {
    const def = draftDef()
    def.nodes = def.nodes.filter((n) => n.id !== 'cond_vip' && n.id !== 'send_vip' && n.id !== 'end_1')
    def.nodes = def.nodes.map((n) => (n.id === 'wait_1' ? { ...n, config: { duration: 'P3D' } } : n))
    const s = scenario('pending', REF_AMY)
    await openReview(WANG, { draft: { ...s.draft!, definition: def } })
    await screen.findByText(/跟 v3 比，這次改了什麼/)
    expect(bodyText()).toContain('刪除 1 個')
    const rows = [...document.querySelectorAll('table tbody tr')]
    expect(rows.length).toBe(1)
    expect(rows[0].textContent).toContain('結束')
  })

  it('管理員（非送審者）：退回、核准並發佈可用；核准 → 對話框 → POST approve', async () => {
    const { api } = await openReview(WANG)
    await expectEnabled('退回')
    await userEvent.click(await expectEnabled('核准並發佈'))
    const d = await screen.findByRole('dialog', { name: '核准並發佈 v4？' })
    await userEvent.click(within(d).getByRole('button', { name: '核准並發佈' }))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/reviews/${RID}/approve`).length).toBe(1))
  })

  it('管理員看自己送的：核准停用並說明，退回可用', async () => {
    await openReview(WANG, { submitter: REF_WANG })
    await expectDisabled('核准並發佈', '不能核准自己送審的版本，請另一位管理員審核。')
    await expectEnabled('退回')
  })

  it('編輯者打開審核網址：可看內容；「只有管理員可以核准或退回」；退回、核准停用並說明「只有管理員可以處理審核。」', async () => {
    await openReview(AMY)
    expect(bodyText()).toContain('只有管理員可以核准或退回')
    expect(bodyText()).toContain('新增 2 個節點')
    await expectDisabled('退回', '只有管理員可以處理審核。')
    await expectDisabled('核准並發佈', '只有管理員可以處理審核。')
  })

  it('檢視者打開審核網址：同樣只能看', async () => {
    await openReview(VIEWER)
    await expectDisabled('退回', '只有管理員可以處理審核。')
    await expectDisabled('核准並發佈', '只有管理員可以處理審核。')
  })

  it('沒有送審中的內容：「這個旅程目前沒有待審核的送審。可能已被撤回、核准或退回。」＋「看審核紀錄」', async () => {
    reviewBackend(WANG, { kind: 'editing' })
    start(REVIEW_PATH)
    expect(await screen.findByText(/這個旅程目前沒有待審核的送審/)).toBeTruthy()
    expect(bodyText()).toContain('可能已被撤回、核准或退回')
    expect(await action('看審核紀錄')).not.toBeNull()
    await expectAbsent('核准並發佈')
  })

  it('404：「找不到這個旅程」＋「回旅程列表」', async () => {
    reviewBackend(WANG, { routes: { [`GET ${API}`]: () => problem(404, 'JOURNEY_NOT_FOUND') } })
    start(REVIEW_PATH)
    expect(await screen.findByText(/找不到這個旅程/)).toBeTruthy()
    expect(await action('回旅程列表')).not.toBeNull()
  })

  it('5xx：role=alert「暫時無法載入這次送審的內容」附請求編號；重試後顯示', async () => {
    let fail = true
    reviewBackend(WANG, {
      routes: {
        [`GET ${API}/draft`]: () =>
          fail ? json({ type: 'about:blank', title: 'x', status: 500, code: 'INTERNAL', detail: 'd', request_id: 'REQ-R-500' }, 500) : json(scenario('pending', REF_AMY).draft),
      },
    })
    start(REVIEW_PATH)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('暫時無法載入這次送審的內容')
    expect(alert.textContent).toContain('REQ-R-500')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('heading', { level: 1, name: /審核 v4/ })
  })

  it('載入中：role=status「載入審核內容中」', async () => {
    const d = deferred<Response>()
    reviewBackend(WANG, { routes: { [`GET ${API}/draft`]: () => d.promise } })
    start(REVIEW_PATH)
    expect(await screen.findByRole('status', { name: '載入審核內容中' })).toBeTruthy()
    d.resolve(json(scenario('pending', REF_AMY).draft))
    await screen.findByRole('heading', { level: 1, name: /審核 v4/ })
  })

  it('核准時 409 REVIEW_NOT_PENDING：提示已被處理與「重新載入」', async () => {
    await openReview(WANG, { routes: { [`POST ${API}/reviews/${RID}/approve`]: () => problem(409, 'REVIEW_NOT_PENDING') } })
    await userEvent.click(await mustAction('核准並發佈'))
    const d = await screen.findByRole('dialog', { name: '核准並發佈 v4？' })
    await userEvent.click(within(d).getByRole('button', { name: '核准並發佈' }))
    await waitFor(() => expect(bodyText()).toMatch(/已被(其他管理員)?處理/))
    expect(await screen.findByRole('button', { name: '重新載入' })).toBeTruthy()
  })
})
