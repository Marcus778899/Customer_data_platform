// @vitest-environment jsdom
/** journey-customer-ui：圖形軌跡（直線）。推導規則 journey-engine §4.5；遮罩 §4.4。假設見 fixtures.tsx。 */
import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  EID, JID, PID, backend, bodyText, deferred, enrollmentDetail, enrollmentDone, enrollmentSummary, gotoNav, json,
  journeyVersion, page, problem, problemWithId, profileDetail, profileSummary, resetEnv, start, teardown, traceEntry,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const VER = `/api/v1/journeys/${JID}/versions/3`

/** 從客戶頁的旅程紀錄點進軌跡頁 */
async function openTrace(routes: Parameters<typeof backend>[0] = {}) {
  const api = backend({
    'GET /api/v1/profiles/search': () => json({ items: [profileSummary({ matched_by: 'PROFILE_ID' })] }),
    [`GET /api/v1/profiles/${PID}`]: () => json(profileDetail()),
    [`GET /api/v1/profiles/${PID}/enrollments`]: () => json(page([enrollmentSummary()])),
    [`GET /api/v1/enrollments/${EID}`]: () => json(enrollmentDetail()),
    [`GET ${VER}`]: () => json(journeyVersion(3)),
    ...routes,
  })
  start('/')
  await gotoNav('客戶')
  await userEvent.type(await screen.findByLabelText('搜尋客戶'), PID)
  await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
  await userEvent.click(await screen.findByRole('link', { name: '棄單挽回（v3）' }))
  return api
}
const node = (name: RegExp) => screen.findByRole('button', { name, hidden: true })
const steps = async () => {
  const h = await screen.findByRole('heading', { name: '逐步紀錄（舊到新）' })
  return h.parentElement!.querySelector('ol')! as HTMLOListElement
}

describe('圖形軌跡：進行中', () => {
  it('標頭：旅程名稱＋狀態徽章、客戶連結、依 v3 執行、Enrollment ID 與資料更新時間、目前狀況（role=status）', async () => {
    await openTrace()
    const title = await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    expect(title.textContent).toContain('等待中')
    const text = bodyText()
    expect(text).toContain('依 v3 執行')
    expect(text).toContain(EID)
    expect(text).toContain('2026-10-18 11:05') // as_of 2026-10-18T03:05:12Z
    expect(screen.getAllByRole('link').some((a) => a.textContent?.includes('01JC'))).toBe(true) // 客戶連結（頭尾縮寫的客戶 ID）
    const sum = screen.getAllByRole('status').find((s) => s.textContent?.includes('目前狀況'))
    expect(sum).toBeTruthy()
    expect(sum!.textContent).toContain('等 3 天')
    expect(screen.getByRole('button', { name: '重新整理' })).toBeTruthy()
  })

  it('節點三態：已經過（開始、加入購物車）、目前位置（等 3 天）、未經過（提醒信、結束）；狀態有文字', async () => {
    await openTrace()
    const state = async (n: RegExp) => (await node(n)).textContent ?? ''
    expect(await state(/開始/)).toContain('已經過')
    expect(await state(/加入購物車/)).toContain('已經過')
    expect(await state(/等 3 天/)).toContain('目前位置')
    const send = await node(/提醒信/)
    expect(send.textContent).not.toMatch(/已經過|目前位置/)
    // 未經過：讀屏可得「未經過」（可見文字或 aria-label）
    const announced = (el: HTMLElement) => `${el.getAttribute('aria-label') ?? ''}${el.textContent}`
    expect(announced(send)).toContain('未經過')
    expect(announced(await node(/結束/))).toContain('未經過')
    expect(announced(await node(/加入購物車/))).toContain('已經過')
    expect(announced(await node(/等 3 天/))).toContain('目前位置')
    // 圖例：三態都有文字說明
    const text = bodyText()
    for (const w of ['已經過', '目前位置', '未經過']) expect(text).toContain(w)
  })

  it('節點為 button[aria-pressed]，圖為 role=group；窄螢幕提示改看文字版；有文字版節點清單', async () => {
    await openTrace()
    for (const n of [/開始/, /加入購物車/, /等 3 天/, /提醒信/, /結束/]) expect((await node(n)).hasAttribute('aria-pressed')).toBe(true)
    expect(document.querySelector('[role="group"]')).toBeTruthy()
    expect(bodyText()).toContain('窄螢幕：圖可左右捲動')
    expect([...document.querySelectorAll('summary')].some((s) => s.textContent?.includes('節點清單'))).toBe(true)
  })

  it('逐步紀錄 <ol>：依 seq 舊到新、每步顯示節點名稱／型別／出口／原因；目前步驟 aria-current="step" 且只有一個', async () => {
    await openTrace()
    const ol = await steps()
    const lis = [...ol.querySelectorAll(':scope > li')]
    expect(lis).toHaveLength(2)
    expect(lis[0].textContent).toContain('加入購物車')
    expect(lis[0].textContent).toContain('EventSourceNode')
    expect(lis[0].textContent).toContain('出口：next')
    expect(lis[0].textContent).toContain('收到事件 cart_item_added，建立 Enrollment。')
    expect(lis[0].textContent).toContain('2026-10-18 10:00:00')
    expect(lis[1].textContent).toContain('等 3 天')
    expect(lis[1].textContent).toContain('WaitDelayNode')
    expect(lis[1].textContent).toContain('等待 P3D，預計 2026-10-21T02:00:00Z 喚醒。')
    expect(lis[0].getAttribute('aria-current')).toBeNull()
    expect(lis[1].getAttribute('aria-current')).toBe('step')
    expect(ol.querySelectorAll('[aria-current="step"]')).toHaveLength(1)
  })

  it('遮罩只依後端標記：masked_paths 內的 evaluated 值有「已遮罩」徽章；未標記的 *** 原樣顯示；未遮罩的值明文', async () => {
    await openTrace({
      [`GET /api/v1/enrollments/${EID}`]: () =>
        json(enrollmentDetail({
          trace: [
            traceEntry(1, 'src_1', 'EventSourceNode', '加入購物車', { evaluated: { event_id: 'ev-plain-1', cart_id: '***', stars: '***' } }),
            traceEntry(2, 'wait_1', 'WaitDelayNode', '等 3 天', { exited_at: null, out_port: null, evaluated: { duration: 'P3D' } }),
          ],
          masked_paths: ['trace[0].evaluated.cart_id'],
        })),
    })
    const first = (await steps()).querySelector(':scope > li')!
    const dd = (key: string) => within(first as HTMLElement).getByText(key, { selector: 'dt' }).nextElementSibling as HTMLElement
    expect(dd('event_id').textContent).toContain('ev-plain-1')
    expect(within(dd('event_id')).queryByText('已遮罩')).toBeNull()
    expect(within(dd('cart_id')).getByText('已遮罩')).toBeTruthy()
    expect(dd('stars').textContent).toContain('***')
    expect(within(dd('stars')).queryByText('已遮罩')).toBeNull()
    expect(bodyText()).toContain('遮罩值無法解除')
  })

  it('載入中：先取 Enrollment 再取定義，期間 h1「載入中…」與 role=status', async () => {
    const d = deferred<Response>()
    await openTrace({ [`GET /api/v1/enrollments/${EID}`]: () => d.promise })
    await screen.findByRole('heading', { level: 1, name: '載入中…' })
    expect(screen.getAllByRole('status').length).toBeGreaterThan(0)
    d.resolve(json(enrollmentDetail()))
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
  })

  it('以 Enrollment 綁定的版本（journey_id＋journey_version）取定義，不是最新版', async () => {
    const api = await openTrace({
      [`GET /api/v1/enrollments/${EID}`]: () => json(enrollmentDetail({ journey_version: 2 })),
      [`GET /api/v1/journeys/${JID}/versions/2`]: () => json(journeyVersion(2, '等 3 天')),
    })
    await node(/等 3 天/)
    expect(api.calls.some((c) => c.path === `/api/v1/journeys/${JID}/versions/2`)).toBe(true)
    expect(api.calls.some((c) => c.path === VER)).toBe(false)
  })
})

describe('圖形軌跡：終態', () => {
  it('已完成：全部節點已經過、沒有目前位置、沒有 aria-current；顯示結束時間與結束原因', async () => {
    await openTrace({ [`GET /api/v1/enrollments/${EID}`]: () => json(enrollmentDone()) })
    const title = await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    expect(title.textContent).toContain('已完成')
    for (const n of [/開始/, /加入購物車/, /等 3 天/, /提醒信/, /結束/]) {
      const b = await node(n)
      expect(`${b.getAttribute('aria-label') ?? ''}${b.textContent}`).toContain('已經過')
    }
    const ol = await steps()
    expect(ol.querySelectorAll(':scope > li')).toHaveLength(4)
    expect(ol.querySelector('[aria-current="step"]')).toBeNull()
    expect(screen.queryByText('● 目前位置')).toBeNull()
    const text = bodyText()
    expect(text).toContain('結束時間')
    expect(text).toContain('2026-10-21 10:00:03')
    expect(text).toContain('結束原因')
    expect(text).toContain('旅程走到結束節點，Enrollment 完成。')
  })
})

describe('圖形軌跡：定義取得失敗與資料對不上', () => {
  it('定義 5xx：提示「無法顯示旅程圖」附 request_id 與重試，下方仍顯示逐步紀錄；重試成功後出現圖', async () => {
    let fail = true
    await openTrace({ [`GET ${VER}`]: () => (fail ? problemWithId(500, 'INTERNAL_ERROR', 'req_c4e2') : json(journeyVersion(3))) })
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('無法顯示旅程圖')
    expect(alert.textContent).toContain('req_c4e2')
    expect((await steps()).querySelectorAll(':scope > li')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: /加入購物車/, hidden: true })).toBeNull()
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await node(/加入購物車/)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('定義 404：同樣退回逐步紀錄', async () => {
    await openTrace({ [`GET ${VER}`]: () => problem(404, 'JOURNEY_NOT_FOUND') })
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    expect((await screen.findByRole('alert')).textContent).toContain('無法顯示旅程圖')
    expect((await steps()).querySelectorAll(':scope > li')).toHaveLength(2)
  })

  it('軌跡的 node_id 不在定義裡（對不上）：不推測、退回逐步紀錄', async () => {
    await openTrace({
      [`GET /api/v1/enrollments/${EID}`]: () =>
        json(enrollmentDetail({
          trace: [
            traceEntry(1, 'ghost_1', 'EventSourceNode', '幽靈節點'),
            traceEntry(2, 'wait_1', 'WaitDelayNode', '等 3 天', { exited_at: null, out_port: null }),
          ],
        })),
    })
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    expect((await screen.findByRole('alert')).textContent).toContain('無法顯示旅程圖')
    const lis = (await steps()).querySelectorAll(':scope > li')
    expect(lis).toHaveLength(2)
    expect(lis[0].textContent).toContain('幽靈節點')
  })

  it('Enrollment 404：「找不到這筆 Enrollment」，不顯示圖與逐步紀錄', async () => {
    await openTrace({ [`GET /api/v1/enrollments/${EID}`]: () => problem(404, 'ENROLLMENT_NOT_FOUND') })
    await screen.findByRole('heading', { name: /找不到這筆 Enrollment/ })
    expect(screen.queryByRole('heading', { name: '逐步紀錄（舊到新）' })).toBeNull()
  })
})
