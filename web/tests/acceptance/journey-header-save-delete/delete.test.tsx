// @vitest-environment jsdom
/**
 * journey-header-save-delete：刪除旅程（canvas-interaction.md §6、設計稿 #dl、docs/design/journey-delete.md、契約 §3.6）。
 * 入口只有標頭「⋯ → 刪除旅程」；僅 DRAFT 且 EDITOR 以上；送審中停用並說明；alertdialog 預設焦點「取消」；
 * 成功導回列表並顯示 6 秒提示；失敗依錯誤碼顯示文案＋請求編號。API 用 mock（DELETE /api/v1/journeys/{journey_id}）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  API, AMY, JOURNEY_URL, NEVER, REF_AMY, VIEWER, WANG, bodyText, callsTo, cleanup, deferred, expectNotUsable, itemNamed, journeyBackend,
  journeySummary, json, openJourney, openMenu, page, prob, reasonIds, resetEnv, screen, sleep, teardown, userEvent, waitFor, within,
} from './helpers'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const DELETE = `DELETE ${API}`
const gone = () => new Response(null, { status: 204 })
const LIST = { 'GET /api/v1/journeys': () => json(page([journeySummary({ journey_id: 'other', name: '其他旅程' })])) }

async function openConfirm(o: Parameters<typeof journeyBackend>[0] = {}) {
  const b = await openJourney({ me: AMY, kind: 'neverPublished', ...o, routes: { ...LIST, ...o.routes } })
  const menu = await openMenu()
  await userEvent.click(itemNamed(menu, '刪除旅程')!)
  const d = await screen.findByRole('alertdialog', { name: '刪除「棄單挽回」？' })
  return { ...b, d }
}
const confirmBtn = (d: HTMLElement) => within(d).getByRole('button', { name: /^刪除旅程$/ })
const cancelBtn = (d: HTMLElement) => within(d).getByRole('button', { name: '取消' })
const off = (b: HTMLElement) => b.getAttribute('aria-disabled') === 'true' || (b as HTMLButtonElement).disabled

describe('入口與權限', () => {
  it.each([['編輯者', AMY], ['管理員', WANG]])('%s：DRAFT 旅程的「⋯」裡有可用的「刪除旅程」', async (_n, me) => {
    await openJourney({ me, kind: 'neverPublished' })
    const menu = await openMenu()
    expect(itemNamed(menu, '刪除旅程')!.getAttribute('aria-disabled')).not.toBe('true')
  })

  it('檢視者：不能刪除（不顯示或停用），點不開對話框、不送請求', async () => {
    const { api } = await openJourney({ me: VIEWER, kind: 'neverPublished' })
    await expectNotUsable('刪除旅程')
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(api.calls.filter((c) => c.method === 'DELETE')).toEqual([])
  })

  it.each([['進行中', 'active'], ['有草稿的進行中', 'editing'], ['已停止', 'aborted']] as const)('%s（已發佈過）：沒有「刪除旅程」', async (_n, kind) => {
    await openJourney({ me: WANG, kind })
    const b = document.querySelector('button[aria-label="更多動作"]')
    if (b) {
      const menu = await openMenu().catch(() => null)
      if (menu) expect(itemNamed(menu, '刪除旅程')).toBeNull()
    }
  })

  it('送審中：「刪除旅程」停用並說明「請先撤回送審」；點下去不開對話框、不送 DELETE', async () => {
    const { api } = await openJourney({ me: AMY, kind: 'pending', submitter: REF_AMY, detail: NEVER })
    const menu = await openMenu()
    const del = itemNamed(menu, '刪除旅程')!
    expect(del.getAttribute('aria-disabled')).toBe('true')
    const why = reasonIds(del).map((i) => document.getElementById(i)?.textContent ?? '').join('')
    expect(why).toMatch(/撤回送審/)
    await userEvent.click(del)
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(callsTo(api, 'DELETE', API)).toEqual([])
  })
})

describe('確認對話框（alertdialog）', () => {
  it('標題「刪除「名稱」？」、「刪除後無法復原。」；預設焦點「取消」；紅色鈕「刪除旅程」；不要求輸入名稱', async () => {
    const { d } = await openConfirm()
    expect(d.getAttribute('aria-modal')).toBe('true')
    const desc = (d.getAttribute('aria-describedby') ?? '').split(/\s+/).map((i) => document.getElementById(i)?.textContent ?? '').join('')
    expect(desc).toContain('刪除後無法復原')
    expect(document.activeElement).toBe(cancelBtn(d))
    expect(confirmBtn(d)).toBeTruthy()
    expect(within(d).queryByRole('textbox')).toBeNull()
    expect(off(confirmBtn(d))).toBe(false)
  })

  it('取消與 Esc：關閉、不送 DELETE、留在旅程頁', async () => {
    const { api, d } = await openConfirm()
    await userEvent.click(cancelBtn(d))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    const menu = await openMenu()
    await userEvent.click(itemNamed(menu, '刪除旅程')!)
    await screen.findByRole('alertdialog')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(callsTo(api, 'DELETE', API)).toEqual([])
    expect(window.location.pathname).toBe(JOURNEY_URL)
  })

  it('確認 → DELETE /api/v1/journeys/{journey_id} 一次；刪除中鈕變「刪除中…」且停用，取消也停用', async () => {
    const gate = deferred<Response>()
    const { api, d } = await openConfirm({ routes: { [DELETE]: () => gate.promise } })
    await userEvent.click(confirmBtn(d))
    await waitFor(() => expect(within(d).getByRole('button', { name: /刪除中…/ })).toBeTruthy())
    expect(off(within(d).getByRole('button', { name: /刪除中…/ }))).toBe(true)
    expect(off(cancelBtn(d))).toBe(true)
    await userEvent.click(within(d).getByRole('button', { name: /刪除中…/ }))
    expect(callsTo(api, 'DELETE', API).length).toBe(1)
    gate.resolve(gone())
    await waitFor(() => expect(window.location.pathname).toBe('/journeys'))
  })
})

describe('成功', () => {
  it('導回「旅程」列表並顯示「已刪除『名稱』」提示，可手動關閉', async () => {
    const { d } = await openConfirm({ routes: { [DELETE]: gone } })
    await userEvent.click(confirmBtn(d))
    await waitFor(() => expect(window.location.pathname).toBe('/journeys'))
    await waitFor(() => expect(bodyText()).toMatch(/已刪除[「『]棄單挽回[」』]/))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /關閉/ }))
    await waitFor(() => expect(bodyText()).not.toMatch(/已刪除[「『]棄單挽回[」』]/))
  })

  it('提示約 6 秒後自動消失（5 秒時還在）', async () => {
    const { d } = await openConfirm({ routes: { [DELETE]: gone } })
    await userEvent.click(confirmBtn(d))
    await waitFor(() => expect(bodyText()).toMatch(/已刪除[「『]棄單挽回[」』]/))
    await sleep(4500)
    expect(bodyText()).toMatch(/已刪除[「『]棄單挽回[」』]/)
    await waitFor(() => expect(bodyText()).not.toMatch(/已刪除[「『]棄單挽回[」』]/), { timeout: 4000 })
  }, 30000)
})

describe('失敗：依錯誤碼顯示文案＋請求編號（契約 §3.6、journey-delete.md）', () => {
  async function failWith(res: Response, rid: string) {
    const { api, d } = await openConfirm({ routes: { [DELETE]: () => res } })
    await userEvent.click(confirmBtn(d))
    await waitFor(() => expect(bodyText()).toContain(rid))
    return api
  }

  it('409 DRAFT_IN_REVIEW：說明剛被送審、先撤回送審；顯示請求編號；停在旅程頁', async () => {
    const api = await failWith(prob(409, 'DRAFT_IN_REVIEW', 'REQ-DEL-409A'), 'REQ-DEL-409A')
    expect(bodyText()).toMatch(/撤回送審/)
    expect(window.location.pathname).toBe(JOURNEY_URL)
    expect(callsTo(api, 'DELETE', API).length).toBe(1)
  })

  it('409 JOURNEY_PUBLISHED：說明旅程已發佈（已有版本）不能刪除；顯示請求編號', async () => {
    await failWith(prob(409, 'JOURNEY_PUBLISHED', 'REQ-DEL-409B'), 'REQ-DEL-409B')
    expect(bodyText()).toMatch(/已發佈|已有版本|發佈過/)
    expect(window.location.pathname).toBe(JOURNEY_URL)
  })

  it('403：說明角色沒有權限；顯示請求編號', async () => {
    await failWith(prob(403, 'FORBIDDEN', 'REQ-DEL-403'), 'REQ-DEL-403')
    expect(bodyText()).toMatch(/權限|角色/)
    expect(window.location.pathname).toBe(JOURNEY_URL)
  })

  it('5xx：通用失敗文案（稍後再試）與請求編號；仍可關閉對話框再試，第二次成功即導回列表', async () => {
    let fail = true
    const { api, d } = await openConfirm({ routes: { [DELETE]: () => (fail ? prob(503, 'UNAVAILABLE', 'REQ-DEL-503') : gone()) } })
    await userEvent.click(confirmBtn(d))
    await waitFor(() => expect(bodyText()).toContain('REQ-DEL-503'))
    expect(bodyText()).toMatch(/失敗|稍後|重試/)
    expect(window.location.pathname).toBe(JOURNEY_URL)
    fail = false
    const dlg = screen.getByRole('alertdialog')
    const retry = within(dlg).queryByRole('button', { name: /重試|刪除旅程/ })
    if (retry) await userEvent.click(retry)
    else {
      await userEvent.click(within(dlg).getByRole('button', { name: /關閉|取消/ }))
      await userEvent.click(itemNamed(await openMenu(), '刪除旅程')!)
      await userEvent.click(confirmBtn(await screen.findByRole('alertdialog')))
    }
    await waitFor(() => expect(window.location.pathname).toBe('/journeys'))
    expect(callsTo(api, 'DELETE', API).length).toBe(2)
  })

  it('404 JOURNEY_NOT_FOUND（別人已刪掉）：直接導回旅程列表，不卡在已不存在的旅程頁', async () => {
    const { d } = await openConfirm({ routes: { [DELETE]: () => prob(404, 'JOURNEY_NOT_FOUND', 'REQ-DEL-404') } })
    await userEvent.click(confirmBtn(d))
    await waitFor(() => expect(window.location.pathname).toBe('/journeys'))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('失敗後旅程資料沒被改動：停在原頁、DELETE 只送一次、沒有成功提示', async () => {
    const api = await failWith(prob(409, 'JOURNEY_PUBLISHED', 'REQ-DEL-X'), 'REQ-DEL-X')
    expect(callsTo(api, 'DELETE', API).length).toBe(1)
    expect(bodyText()).not.toMatch(/已刪除[「『]/)
  })
})
