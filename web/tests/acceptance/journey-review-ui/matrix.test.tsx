// @vitest-environment jsdom
/** journey-review-ui：旅程頁頁首按鈕的角色 × 狀態可見性（rv-matrix、rv-bars）；停用鈕保留並附原因；入口（編輯、新增旅程）。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  API, AMY, JID, REF_DAMING, REF_WANG, VIEWER, WANG, action, bodyText, callsTo, cleanup, expectAbsent, expectDisabled, expectEnabled,
  expectNotUsable, journeyBackend, json, mustAction, openJourney, resetEnv, screen, start, teardown, userEvent, waitFor,
  journeySummary, page, REF_AMY, isDisabled,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const VIEW_ONLY = '你的角色（檢視者）只能查看。'
const OWN_APPROVE = '不能核准自己送審的版本，請另一位管理員審核。'
const NOT_PUBLISHED = '尚未發佈的旅程不能停止或刪除草稿'

describe('進行中、沒有草稿', () => {
  it('檢視者：編輯、停止旅程停用並說明「你的角色（檢視者）只能查看。」；點下去不開對話框、不送請求', async () => {
    const { api } = await openJourney({ me: VIEWER, kind: 'active' })
    const edit = await expectDisabled('編輯', VIEW_ONLY)
    const stop = await expectDisabled('停止旅程', VIEW_ONLY)
    await userEvent.click(stop)
    await userEvent.click(edit)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.calls.filter((c) => c.method !== 'GET')).toEqual([])
    await expectAbsent('送審')
    await expectAbsent('核准並發佈')
    await expectAbsent('退回')
  })

  it.each([['編輯者', AMY], ['管理員', WANG]])('%s：編輯（建立草稿）、停止旅程可用', async (_n, me) => {
    await openJourney({ me, kind: 'active' })
    await expectEnabled('編輯')
    await expectEnabled('停止旅程')
    await expectAbsent('核准並發佈')
    await expectAbsent('撤回送審')
  })

  it('編輯者按「編輯」：沒有草稿 → PUT 草稿（revision 0、帶最新版本的定義）後進入編輯畫布', async () => {
    const { api } = await openJourney({ me: AMY, kind: 'active' })
    await userEvent.click(await mustAction('編輯'))
    await waitFor(() => expect(window.location.pathname).toBe(`/journeys/${JID}/edit`))
    const put = callsTo(api, 'PUT', `${API}/draft`)
    expect(put.length).toBeGreaterThan(0)
    const body = put[0].body as { revision: number; definition: { nodes: unknown[] } }
    expect(body.revision).toBe(0)
    expect(Array.isArray(body.definition.nodes)).toBe(true)
    expect(body.definition.nodes.length).toBeGreaterThan(0)
  })

  it('已有草稿：「編輯」直接進入編輯畫布，不再 PUT', async () => {
    const { api } = await openJourney({ me: AMY, kind: 'editing' })
    await userEvent.click(await mustAction('編輯'))
    await waitFor(() => expect(window.location.pathname).toBe(`/journeys/${JID}/edit`))
    expect(callsTo(api, 'PUT', `${API}/draft`)).toEqual([])
  })
})

describe('草稿編輯中（尚未送審）', () => {
  it('檢視者：編輯停用附原因', async () => {
    await openJourney({ me: VIEWER, kind: 'editing' })
    await expectDisabled('編輯', VIEW_ONLY)
    await expectNotUsable('捨棄草稿')
    await expectNotUsable('停止旅程')
  })

  it.each([['編輯者', AMY], ['管理員', WANG]])('%s：編輯、捨棄草稿、停止旅程可用', async (_n, me) => {
    await openJourney({ me, kind: 'editing' })
    for (const n of ['編輯', '捨棄草稿', '停止旅程']) await expectEnabled(n)
  })

  it('捨棄草稿 → DELETE 草稿', async () => {
    const { api } = await openJourney({
      me: AMY, kind: 'editing',
      routes: { [`DELETE ${API}/draft`]: () => new Response(null, { status: 204 }) },
    })
    await userEvent.click(await mustAction('捨棄草稿'))
    const dlg = screen.queryByRole('dialog')
    if (dlg) {
      const ok = [...dlg.querySelectorAll('button')].filter((b) => /捨棄/.test(b.textContent ?? '')).at(-1)
      if (ok) await userEvent.click(ok)
    }
    await waitFor(() => expect(callsTo(api, 'DELETE', `${API}/draft`).length).toBe(1))
  })
})

describe('從未發佈的草稿（status DRAFT）', () => {
  it('編輯者：編輯可用；捨棄草稿、停止旅程停用並說明「尚未發佈的旅程不能停止或刪除草稿」', async () => {
    const { api } = await openJourney({ me: AMY, kind: 'neverPublished' })
    await expectEnabled('編輯')
    const discard = await expectDisabled('捨棄草稿', NOT_PUBLISHED)
    const stop = await expectDisabled('停止旅程', NOT_PUBLISHED)
    await userEvent.click(discard)
    await userEvent.click(stop)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.calls.filter((c) => c.method !== 'GET')).toEqual([])
  })
})

describe('送審中', () => {
  it('編輯者看自己送的：撤回送審、停止旅程可用；沒有核准、退回；顯示送審者與草稿鎖定說明', async () => {
    await openJourney({ me: AMY, kind: 'pending', submitter: REF_AMY })
    await expectEnabled('撤回送審')
    await expectEnabled('停止旅程')
    await expectAbsent('核准並發佈')
    await expectAbsent('退回')
    expect(bodyText()).toContain('陳小美')
    expect(bodyText()).toMatch(/審核中/)
    expect(bodyText()).toContain('草稿已鎖定，不能修改')
  })

  it('編輯者看別人送的：撤回送審停用並說明「只有送審者或管理員可以撤回。」；停止旅程可用；沒有核准、退回', async () => {
    const { api } = await openJourney({ me: AMY, kind: 'pending', submitter: REF_DAMING })
    const w = await expectDisabled('撤回送審', '只有送審者或管理員可以撤回。')
    await expectEnabled('停止旅程')
    await expectAbsent('核准並發佈')
    await expectAbsent('退回')
    await userEvent.click(w)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.calls.filter((c) => c.method !== 'GET')).toEqual([])
  })

  it('檢視者：沒有任何可用的撤回、核准、退回、停止', async () => {
    await openJourney({ me: VIEWER, kind: 'pending', submitter: REF_AMY })
    for (const n of ['撤回送審', '核准並發佈', '退回', '停止旅程']) await expectNotUsable(n)
    expect(bodyText()).toContain('陳小美')
  })

  it('管理員看別人送的：核准並發佈、退回、撤回送審、停止旅程都可用', async () => {
    await openJourney({ me: WANG, kind: 'pending', submitter: REF_AMY })
    for (const n of ['核准並發佈', '退回', '撤回送審', '停止旅程']) await expectEnabled(n)
  })

  it('管理員看自己送的：核准並發佈停用並說明「不能核准自己送審的版本，請另一位管理員審核。」；點下去不開對話框、不送請求；退回、撤回、停止可用', async () => {
    const { api } = await openJourney({ me: WANG, kind: 'pending', submitter: REF_WANG })
    const approve = await expectDisabled('核准並發佈', OWN_APPROVE)
    await expectEnabled('退回')
    await expectEnabled('撤回送審')
    await expectEnabled('停止旅程')
    await userEvent.click(approve)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.calls.filter((c) => c.method === 'POST')).toEqual([])
  })
})

describe('已退回', () => {
  it('編輯者：看得到退回者與原因、有「修改後再送審」；捨棄草稿、停止旅程可用', async () => {
    await openJourney({ me: AMY, kind: 'rejected', submitter: REF_AMY })
    expect(bodyText()).toContain('王小華')
    expect(bodyText()).toContain('等待時間請改為 2 天')
    await expectEnabled('修改後再送審')
    await expectEnabled('捨棄草稿')
    await expectEnabled('停止旅程')
    await expectAbsent('撤回送審')
    await expectAbsent('核准並發佈')
  })

  it('「修改後再送審」導向編輯畫布', async () => {
    await openJourney({ me: AMY, kind: 'rejected' })
    await userEvent.click(await mustAction('修改後再送審'))
    await waitFor(() => expect(window.location.pathname).toBe(`/journeys/${JID}/edit`))
  })

  it('檢視者：看得到退回原因，不能修改後再送審', async () => {
    await openJourney({ me: VIEWER, kind: 'rejected' })
    expect(bodyText()).toContain('等待時間請改為 2 天')
    await expectNotUsable('修改後再送審')
  })
})

describe('已停止', () => {
  it('提示「這個旅程已停止，不能再修改或發佈」＋停止者、原因；有「審核紀錄」；不能送審、核准、退回、修改後再送審；編輯與停止不可用', async () => {
    await openJourney({ me: WANG, kind: 'aborted' })
    expect(bodyText()).toContain('這個旅程已停止，不能再修改或發佈')
    expect(bodyText()).toContain('王小華')
    expect(bodyText()).toContain('活動結束')
    expect(await action('審核紀錄')).not.toBeNull()
    for (const n of ['送審', '核准並發佈', '退回', '修改後再送審', '編輯', '停止旅程']) await expectNotUsable(n)
  })
})

describe('審核紀錄入口', () => {
  it('檢視者也有「審核紀錄」', async () => {
    await openJourney({ me: VIEWER, kind: 'active' })
    expect(await action('審核紀錄')).not.toBeNull()
  })
})

describe('新增旅程入口（旅程列表）', () => {
  const list = () => json(page([journeySummary({ latest_review: null })]))

  it('編輯者以上有「新增旅程」，點下去進入新建草稿畫面（旅程名稱＋建立草稿）', async () => {
    journeyBackend({ me: AMY, routes: { 'GET /api/v1/journeys': list } })
    start('/journeys')
    await screen.findByRole('link', { name: '棄單挽回' })
    const add = await mustAction('新增旅程')
    expect(isDisabled(add)).toBe(false)
    await userEvent.click(add)
    await screen.findByLabelText('旅程名稱')
    expect(screen.getByRole('button', { name: '建立草稿' })).toBeTruthy()
    expect(window.location.pathname).toBe('/journeys/new')
  })

  it('檢視者看不到「新增旅程」；篩選與列表照常', async () => {
    journeyBackend({ me: VIEWER, routes: { 'GET /api/v1/journeys': list } })
    start('/journeys')
    await screen.findByRole('link', { name: '棄單挽回' })
    await expectAbsent('新增旅程')
  })
})
