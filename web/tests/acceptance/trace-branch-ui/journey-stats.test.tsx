// @vitest-environment jsdom
/** trace-branch-ui：旅程頁各節點人數（journeys.md §5；設計稿 st-view、st-states）。假設見 fixtures.tsx。 */
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  API, JID, STATS3, announced, bodyText, branchVersion, card, deferred, json, nodeStats, norm, openJourney,
  problemWithId, resetEnv, screen, teardown, userEvent, waitFor, within, zeroStats,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const statsCalls = (api: ReturnType<typeof openJourney>, path = STATS3) => api.calls.filter((c) => c.method === 'GET' && c.path === path)

describe('節點人數（st-view）', () => {
  it('每個節點卡顯示「人數」與「目前」（千分位），讀屏標籤也帶數字；StartNode 不列統計', async () => {
    const api = openJourney()
    const ev = await card(/等結帳 24h/)
    await waitFor(() => expect(norm(ev)).toMatch(/1,198\s*人數/))
    expect(norm(ev)).toMatch(/98\s*目前/)
    expect(announced(ev)).toContain('1,198')
    expect(announced(ev)).toContain('98')
    expect(norm(await card(/加入購物車/))).toMatch(/1,284\s*人數/)
    expect(norm(await card(/等 1 小時/))).toMatch(/86\s*目前/)
    expect(norm(await card(/結帳完成/))).toMatch(/1,100\s*人數/)
    expect(norm(await card(/開始/))).not.toMatch(/人數/)
    expect(statsCalls(api)).toHaveLength(1)
  })

  it('標示資料時間（as_of 本地時間）；「重新整理」重打 node-stats', async () => {
    const api = openJourney()
    await waitFor(() => expect(bodyText()).toContain('2026-11-17 10:42'))
    expect(bodyText()).toContain('重新整理才更新')
    await userEvent.click(screen.getByRole('button', { name: '重新整理' }))
    await waitFor(() => expect(statsCalls(api).length).toBe(2))
  })

  it('迴圈邊標「再等一輪」；旅程頁不疊軌跡（沒有已經過／目前位置）', async () => {
    openJourney()
    await card(/再次提醒/)
    await waitFor(() => expect(bodyText()).toContain('再等一輪'))
    expect(bodyText()).not.toMatch(/已經過|目前位置/)
  })

  it('選取節點：右側面板顯示人數、進入次數、目前停在這裡、各出口人次；迴圈節點附說明；可關閉', async () => {
    openJourney()
    const ev = await card(/等結帳 24h/)
    await waitFor(() => expect(norm(ev)).toMatch(/1,198\s*人數/))
    await userEvent.click(ev)
    expect(ev.getAttribute('aria-pressed')).toBe('true')
    const panel = await screen.findByRole('complementary', { name: /等結帳 24h/ })
    const t = norm(panel)
    expect(within(panel).getByRole('heading', { name: /等結帳 24h/ })).toBeTruthy()
    expect(t).toMatch(/人數（曾進入的客戶）\s*1,198/)
    expect(t).toMatch(/進入次數（含迴圈）\s*1,401/)
    expect(t).toMatch(/目前停在這裡\s*98/)
    expect(t).toContain('離開時走的出口（人次）')
    expect(t).toMatch(/事件發生\s*→\s*結帳完成\s*1,100/)
    expect(t).toMatch(/逾時\s*→\s*再次提醒\s*203/)
    expect(t).toContain('這個節點在迴圈裡')
    await userEvent.click(within(panel).getByRole('button', { name: '關閉面板' }))
    await waitFor(() => expect(screen.queryByRole('complementary', { name: /等結帳 24h/ })).toBeNull())
    expect(ev.getAttribute('aria-pressed')).toBe('false')
  })

  it('分流節點的出口：是／否各自的人次；非迴圈節點沒有迴圈說明', async () => {
    openJourney()
    const c = await card(/是 VIP？/)
    await waitFor(() => expect(norm(c)).toMatch(/1,198\s*人數/))
    await userEvent.click(c)
    const t = norm(await screen.findByRole('complementary', { name: /是 VIP？/ }))
    expect(t).toMatch(/是\s*→\s*VIP 專屬關心\s*311/)
    expect(t).toMatch(/否\s*→\s*一般提醒\s*887/)
    expect(t).not.toContain('這個節點在迴圈裡')
  })

  it('切換版本：統計跟著該版本（versions/2/node-stats）', async () => {
    const V2 = `${API}/versions/2/node-stats`
    const api = openJourney({
      [`GET ${API}/versions/2`]: () => json(branchVersion(2)),
      [`GET ${V2}`]: () => json({ ...nodeStats(2), items: nodeStats(2).items.map((i) => ({ ...i, entered: 7, current: 1 })) }),
    })
    await waitFor(() => expect(statsCalls(api)).toHaveLength(1))
    await userEvent.selectOptions(await screen.findByLabelText('版本'), '2')
    await waitFor(() => expect(statsCalls(api, V2)).toHaveLength(1))
    await waitFor(async () => expect(norm(await card(/等結帳 24h/))).toMatch(/7\s*人數/))
  })

  it('尚未發佈（沒有任何版本）：不打 node-stats', async () => {
    const api = openJourney({}, `/journeys/${JID}`, { versions: [], latest_version: null, status: 'DRAFT', published_at: null })
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    expect(bodyText()).toContain('尚未發佈任何版本')
    expect(api.calls.filter((c) => c.path.endsWith('/node-stats'))).toEqual([])
  })
})

describe('節點人數的狀態（st-states）', () => {
  it('載入中：圖先出現、統計區標示載入（aria-busy 或 status），卡片不顯示人數；回來後補上', async () => {
    const d = deferred<Response>()
    openJourney({ [`GET ${STATS3}`]: () => d.promise })
    const ev = await card(/等結帳 24h/)
    expect(await card(/是 VIP？/)).toBeTruthy()
    const busy = document.querySelector('[aria-busy="true"]') ??
      screen.queryAllByRole('status').find((s) => /載入/.test(s.textContent ?? ''))
    expect(busy).toBeTruthy()
    expect(norm(ev)).not.toMatch(/人數/)
    d.resolve(json(nodeStats()))
    await waitFor(() => expect(norm(ev)).toMatch(/1,198\s*人數/))
    expect(document.querySelector('[aria-busy="true"]')).toBeNull()
  })

  it('失敗：圖與設定照常；警示條說明、請求編號、重試；重試成功後補上人數', async () => {
    let fail = true
    const api = openJourney({
      [`GET ${STATS3}`]: () => (fail ? problemWithId(500, 'INTERNAL_ERROR', 'req_stat_01') : json(nodeStats())),
    })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('各節點人數暫時取不到')
    expect(alert.textContent).toContain('旅程圖與節點設定不受影響')
    expect(alert.textContent).toContain('req_stat_01')
    const ev = await card(/等結帳 24h/)
    expect(norm(ev)).not.toMatch(/人數/)
    await userEvent.click(ev)
    expect(await screen.findByRole('complementary', { name: /等結帳 24h/ })).toBeTruthy()
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await waitFor(() => expect(statsCalls(api).length).toBe(2))
    await waitFor(() => expect(norm(ev)).toMatch(/1,198\s*人數/))
    expect(screen.queryByText(/各節點人數暫時取不到/)).toBeNull()
  })

  it('尚無客戶：說明文字，卡片顯示 0', async () => {
    openJourney({ [`GET ${STATS3}`]: () => json(zeroStats()) })
    await waitFor(() => expect(bodyText()).toContain('這個版本還沒有客戶進入'))
    expect(bodyText()).toContain('有客戶進入後，這裡會顯示每個節點的人數')
    expect(norm(await card(/等結帳 24h/))).toMatch(/0\s*人數/)
    expect(screen.queryByText(/暫時取不到/)).toBeNull()
  })

  it('有客戶時不出現「尚無客戶」說明', async () => {
    openJourney()
    await waitFor(async () => expect(norm(await card(/加入購物車/))).toMatch(/1,284\s*人數/))
    expect(bodyText()).not.toContain('這個版本還沒有客戶進入')
  })

  it('審核動作列不受影響：頁首仍有「審核紀錄」連結與版本下拉', async () => {
    openJourney()
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    expect(screen.getByRole('link', { name: '審核紀錄' })).toBeTruthy()
    expect(screen.getByLabelText('版本')).toBeTruthy()
  })
})
