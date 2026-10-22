// @vitest-environment jsdom
/** journey-customer-ui：客戶頁（基本資料、遮罩、參加過的旅程）。假設見 fixtures.tsx。 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  PID, backend, bodyText, deferred, enrollmentSummary, gotoNav, json, page, params, problem, problemWithId,
  profileDetail, profileSummary, resetEnv, start, teardown,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const ENR = `/api/v1/profiles/${PID}/enrollments`
const enrollments = () => [
  enrollmentSummary(),
  enrollmentSummary({
    enrollment_id: 'e2', journey_id: 'j2', journey_name: '新會員歡迎', journey_version: 7, status: 'COMPLETED',
    current_node_name: '結束', current_node_type: 'EndNode', enrolled_at: '2026-09-02T06:30:00Z', terminated_at: '2026-09-04T06:31:00Z',
  }),
]

async function openCustomer(routes: Parameters<typeof backend>[0] = {}) {
  const api = backend({
    'GET /api/v1/profiles/search': () => json({ items: [profileSummary({ profile_id: PID, matched_by: 'PROFILE_ID' })] }),
    [`GET /api/v1/profiles/${PID}`]: () => json(profileDetail()),
    [`GET ${ENR}`]: () => json(page(enrollments())),
    ...routes,
  })
  start('/')
  await gotoNav('客戶')
  await userEvent.type(await screen.findByLabelText('搜尋客戶'), PID)
  await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
  return api
}
const h1 = () => screen.findByRole('heading', { level: 1 })
const section = async (name: string) => (await screen.findByRole('heading', { name })).closest('section, div.card, div') as HTMLElement

describe('客戶頁：基本資料', () => {
  it('左：客戶 ID（完整值＋複製鈕）、建立／最近更新／資料時間（本地時區）、識別碼表、客戶屬性；標題為客戶 ID（沒有 name）', async () => {
    await openCustomer()
    const title = await h1()
    expect(title.textContent).toMatch(/^客戶/)
    expect(title.textContent).toContain('…') // 長 ID 只顯示頭尾
    await screen.findByRole('heading', { name: '基本資料' })
    expect(bodyText()).toContain(PID) // 完整值在客戶頁
    expect(screen.getByRole('button', { name: '複製客戶 ID' })).toBeTruthy()
    const text = bodyText()
    expect(text).toContain('2026-05-03 11:20') // created_at 2026-05-03T03:20:00Z
    expect(text).toContain('2026-11-01 16:00') // updated_at
    expect(text).toContain('2026-11-01 16:05') // as_of
    const idTable = screen.getByRole('table', { name: '識別碼' })
    expect(within(idTable).getAllByRole('columnheader').map((h) => h.textContent?.trim())).toEqual(['類型', '值'])
    expect(idTable.textContent).toContain('email')
    expect(idTable.textContent).toContain('會員編號')
    expect(idTable.textContent).toContain('M-20418')
    expect(screen.getByRole('heading', { name: '客戶屬性' })).toBeTruthy()
    expect(text).toContain('tier')
    expect(text).toContain('regular')
    await waitFor(() => expect(document.activeElement).toBe(title))
  })

  it('標題規則：name 存在且未遮罩 → 用姓名（客戶 ID 移到副標）', async () => {
    await openCustomer({
      [`GET /api/v1/profiles/${PID}`]: () =>
        json(profileDetail({ attributes: { name: '陳大華', tier: 'regular' }, masked_paths: ['identifiers[0].value'] })),
    })
    const title = await h1()
    await waitFor(() => expect(title.textContent).toContain('陳大華'))
    expect(title.textContent).not.toContain('…')
    expect(bodyText()).toContain(PID)
  })

  it('標題規則：name 在 masked_paths（值為 ***）→ 用客戶 ID，不以 *** 當標題', async () => {
    await openCustomer({
      [`GET /api/v1/profiles/${PID}`]: () =>
        json(profileDetail({ attributes: { name: '***', tier: 'regular' }, masked_paths: ['attributes.name'] })),
    })
    const title = await h1()
    await screen.findByRole('heading', { name: '基本資料' })
    expect(title.textContent).toMatch(/^客戶/)
    expect(title.textContent).not.toContain('*')
  })

  it('遮罩只依後端標記：masked_paths／masked 的值顯示「已遮罩」徽章；未標記的 *** 原樣顯示、無徽章；遮罩值無複製鈕', async () => {
    await openCustomer({
      [`GET /api/v1/profiles/${PID}`]: () =>
        json(profileDetail({
          attributes: { tier: 'regular', birthday: '***', note: '***' },
          masked_paths: ['attributes.birthday', 'identifiers[0].value'],
        })),
    })
    await screen.findByRole('heading', { name: '客戶屬性' })
    const idTable = screen.getByRole('table', { name: '識別碼' })
    const emailRow = within(idTable).getByText('email').closest('tr')!
    expect(emailRow.textContent).toContain('a***@example.com')
    expect(within(emailRow).getByText('已遮罩')).toBeTruthy()
    const memberRow = within(idTable).getByText('會員編號').closest('tr')!
    expect(within(memberRow).queryByText('已遮罩')).toBeNull()
    const dd = (key: string) => screen.getByText(key, { selector: 'dt' }).nextElementSibling as HTMLElement
    expect(within(dd('birthday')).getByText('已遮罩')).toBeTruthy()
    expect(dd('note').textContent).toContain('***')
    expect(within(dd('note')).queryByText('已遮罩')).toBeNull()
    expect(within(dd('tier')).queryByText('已遮罩')).toBeNull()
    expect(bodyText()).toContain('遮罩值無法解除')
    expect(within(emailRow).queryByRole('button')).toBeNull()
  })

  it('未知 IdentifierType 顯示原值、不報錯（DESIGN-04 §5）', async () => {
    await openCustomer({
      [`GET /api/v1/profiles/${PID}`]: () =>
        json(profileDetail({
          identifiers: [
            { type: 'EMAIL', value: 'a***@example.com', masked: true },
            { type: 'TWITTER_HANDLE', value: '@amy_tw', masked: false },
          ],
        })),
    })
    const idTable = await screen.findByRole('table', { name: '識別碼' })
    const row = within(idTable).getByText('TWITTER_HANDLE').closest('tr')!
    expect(row.textContent).toContain('@amy_tw')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('「同時顯示 UTC 原值」勾選後時間旁加註 UTC 原值', async () => {
    await openCustomer()
    await screen.findByRole('heading', { name: '基本資料' })
    expect(bodyText()).not.toContain('2026-05-03T03:20:00Z')
    await userEvent.click(screen.getByLabelText('同時顯示 UTC 原值'))
    expect(bodyText()).toContain('2026-05-03T03:20:00Z')
  })

  it('404 PROFILE_NOT_FOUND：「找不到這位客戶」＋回搜尋連結（與 5xx 分開）', async () => {
    await openCustomer({ [`GET /api/v1/profiles/${PID}`]: () => problem(404, 'PROFILE_NOT_FOUND') })
    await screen.findByRole('heading', { level: 1, name: '找不到這位客戶' })
    expect(screen.getByRole('link', { name: '回搜尋' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '重試' })).toBeNull()
  })

  it('基本資料 5xx：alert 附 request_id 與重試，重試後顯示', async () => {
    let fail = true
    await openCustomer({
      [`GET /api/v1/profiles/${PID}`]: () => (fail ? problemWithId(500, 'INTERNAL_ERROR', 'req_pf1') : json(profileDetail())),
    })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('req_pf1')
    expect(bodyText()).not.toContain('找不到這位客戶')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('heading', { name: '基本資料' })
  })

  it('載入中：h1「載入中…」骨架（role=status）', async () => {
    const d = deferred<Response>()
    await openCustomer({ [`GET /api/v1/profiles/${PID}`]: () => d.promise })
    await screen.findByRole('heading', { level: 1, name: '載入中…' })
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    d.resolve(json(profileDetail()))
    await screen.findByRole('heading', { name: '基本資料' })
  })
})

describe('客戶頁：參加過的旅程', () => {
  it('表格：旅程（版本）、狀態、目前／最後節點、進入時間、結束時間、Enrollment ID；新到舊；未結束的結束時間為「—」', async () => {
    const api = await openCustomer()
    const table = await screen.findByRole('table', { name: /參加過/ })
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent?.trim()))
      .toEqual(['旅程（版本）', '狀態', '目前／最後節點', '進入時間', '結束時間', 'Enrollment ID'])
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByRole('link', { name: '棄單挽回（v3）' })).toBeTruthy()
    expect(rows[0].textContent).toContain('等待中')
    expect(rows[0].textContent).toContain('等 3 天')
    expect(rows[0].textContent).toContain('2026-10-18 10:00')
    expect(rows[0].textContent).toContain('—')
    expect(rows[1].textContent).toContain('新會員歡迎（v7）')
    expect(rows[1].textContent).toContain('已完成')
    expect(rows[1].textContent).toContain('2026-09-04 14:31')
    expect(api.calls.some((c) => c.path === ENR)).toBe(true)
  })

  it('cursor 載入更多', async () => {
    const api = await openCustomer({
      [`GET ${ENR}`]: (c) =>
        params(c).get('cursor') === 'c9'
          ? json(page([enrollmentSummary({ enrollment_id: 'e3', journey_name: '第二頁旅程' })]))
          : json(page(enrollments(), 'c9')),
    })
    await screen.findByRole('table', { name: /參加過/ })
    await userEvent.click(await screen.findByRole('button', { name: '載入更多' }))
    await screen.findByRole('link', { name: /第二頁旅程/ })
    expect(api.calls.some((c) => c.path === ENR && params(c).get('cursor') === 'c9')).toBe(true)
    expect(screen.queryByRole('button', { name: '載入更多' })).toBeNull()
  })

  it('尚無旅程：「還沒有參加任何旅程」並說明何時出現；基本資料照常', async () => {
    await openCustomer({ [`GET ${ENR}`]: () => json(page([])) })
    await screen.findByRole('heading', { name: '還沒有參加任何旅程' })
    expect(bodyText()).toContain('客戶觸發旅程的入口事件後，會顯示在這裡')
    expect(screen.getByRole('heading', { name: '基本資料' })).toBeTruthy()
  })

  it('旅程紀錄失敗只影響該區塊：基本資料仍顯示，區塊內 alert 附 request_id 與重試；重試成功', async () => {
    let fail = true
    await openCustomer({
      [`GET ${ENR}`]: () => (fail ? problemWithId(500, 'INTERNAL_ERROR', 'req_91ab') : json(page(enrollments()))),
    })
    await screen.findByRole('heading', { name: '基本資料' })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('無法載入旅程紀錄')
    expect(alert.textContent).toContain('req_91ab')
    expect(screen.getByRole('table', { name: '識別碼' })).toBeTruthy()
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('table', { name: /參加過/ })
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('基本資料已顯示時，旅程紀錄仍在載入也不阻擋（兩個 API 分開處理）', async () => {
    const d = deferred<Response>()
    await openCustomer({ [`GET ${ENR}`]: () => d.promise })
    await screen.findByRole('heading', { name: '基本資料' })
    expect(screen.queryByRole('table', { name: /參加過/ })).toBeNull()
    d.resolve(json(page(enrollments())))
    await screen.findByRole('table', { name: /參加過/ })
  })

  it('同一頁以 section 呈現「基本資料」與「參加過的旅程」兩個區塊標題', async () => {
    await openCustomer()
    await section('基本資料')
    await section('參加過的旅程')
  })
})
