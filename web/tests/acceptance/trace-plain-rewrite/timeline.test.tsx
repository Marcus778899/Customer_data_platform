// @vitest-environment jsdom
/** 軌跡頁白話改版：走過的步驟時間軸、訊息發送紀錄（enrollment-trace.md「畫面」3～4、步驟標題）。 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DETAIL, EID, NOW_EARLY, NOW_LATE, VER, aborted, completed, delivery, failed, freezeNow, heading, journeyVersion, json,
  mainText, norm, openTrace, problem, resetEnv, stepItems, teardown, timeline, traceEntry, unfreeze, waiting,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown(); unfreeze() })

describe('時間軸', () => {
  it('<ol>：標題＋狀態字＋說明＋時間；已完成、目前在這裡、接下來；StartNode 不顯示', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    const items = stepItems(await timeline())
    expect(items).toHaveLength(4)
    expect(norm(items[0])).toContain('加入購物車，進入旅程')
    expect(norm(items[0])).toContain('已完成')
    expect(norm(items[0])).toContain('今天 10:00')
    expect(norm(items[1])).toContain('等 3 天')
    expect(norm(items[1])).toContain('目前在這裡')
    expect(norm(items[1])).toContain('預計 10/21（三）10:00')
    expect(norm(items[2])).toContain('發送訊息：提醒信')
    expect(norm(items[2])).toContain('接下來')
    expect(norm(items[3])).toContain('旅程結束')
    expect(norm(items[3])).toContain('接下來')
    expect(items.map(norm).join(' ')).not.toMatch(/StartNode|「?開始」?\s*已完成/)
  })

  it('目前步驟 aria-current="step" 且只有一個；其他步驟沒有', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    const ol = await timeline()
    const cur = ol.querySelectorAll('[aria-current="step"]')
    expect(cur).toHaveLength(1)
    expect(norm(cur[0])).toContain('等 3 天')
  })

  it('已走過的步驟依 seq 由舊到新；同一節點第二次經過仍各自一列', async () => {
    freezeNow(NOW_EARLY)
    const d = waiting({
      trace: [
        traceEntry(1, 'src_1', 'EventSourceNode', '加入購物車'),
        traceEntry(2, 'wait_1', 'WaitDelayNode', '等 3 天', { exited_at: '2026-10-18T02:30:00Z' }),
        traceEntry(3, 'wait_1', 'WaitDelayNode', '等 3 天', { exited_at: null, out_port: null }),
      ],
    })
    openTrace({}, d)
    await heading()
    const items = stepItems(await timeline()).filter((li) => !norm(li).includes('接下來'))
    expect(items).toHaveLength(3)
    expect(norm(items[0])).toContain('加入購物車')
    expect(norm(items[2])).toContain('目前在這裡')
  })

  it('SendMessageNode 沒有名稱：退回型別白話名「發送訊息」；EventSourceNode 沒有名稱：「進入旅程」', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completed({
      trace: [
        traceEntry(1, 'src_1', 'EventSourceNode', null),
        traceEntry(2, 'send_1', 'SendMessageNode', null, { out_port: 'sent' }),
        traceEntry(3, 'end_1', 'EndNode', '結束', { out_port: null }),
      ],
    }), )
    await heading()
    const text = (await timeline()).textContent ?? ''
    expect(text).toContain('進入旅程')
    expect(text).toContain('發送訊息')
    expect(text).not.toMatch(/Node|null|未命名/)
  })

  it('WaitDelayNode 標題把 ISO 期間轉中文：PT2H → 等 2 小時（定義取自綁定版本；不顯示 PT2H）', async () => {
    freezeNow(NOW_EARLY)
    const base = journeyVersion()
    const def = { ...base, nodes: base.nodes.map((n) => (n.id === 'wait_1' ? { ...n, name: null, config: { duration: 'PT2H' } } : n)) as unknown[] }
    openTrace({ [`GET ${VER}`]: () => json(def) }, waiting({
      trace: [
        waiting().trace[0],
        traceEntry(2, 'wait_1', 'WaitDelayNode', null, { exited_at: null, out_port: null, evaluated: { duration: 'PT2H' } }),
      ],
    }))
    await heading()
    const text = (await timeline()).textContent ?? ''
    expect(text).toContain('等 2 小時')
    expect(text).not.toContain('PT2H')
  })

  it('已走完：全部「已完成」，沒有「接下來」「目前在這裡」，沒有 aria-current', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completed())
    await heading()
    const ol = await timeline()
    const items = stepItems(ol)
    expect(items).toHaveLength(4)
    for (const li of items) expect(norm(li)).toContain('已完成')
    expect(ol.textContent).not.toMatch(/接下來|目前在這裡/)
    expect(ol.querySelector('[aria-current]')).toBeNull()
    expect(norm(items[2])).toContain('發送訊息：提醒信')
    expect(norm(items[3])).toContain('旅程結束')
  })

  it('發生錯誤：出問題的步驟標「發生問題」，後面不再列「接下來」', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, failed())
    await heading()
    const ol = await timeline()
    const items = stepItems(ol)
    const bad = items.find((li) => norm(li).includes('發送訊息：提醒信'))!
    expect(norm(bad)).toContain('發生問題')
    expect(ol.textContent).not.toContain('接下來')
  })

  it('已中止：停下的步驟標「已中止」，後面不再列「接下來」', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, aborted())
    await heading()
    const ol = await timeline()
    const stopped = stepItems(ol).find((li) => norm(li).includes('等 3 天'))!
    expect(norm(stopped)).toContain('已中止')
    expect(norm(stopped)).toContain('10/19（一）15:20')
    expect(ol.textContent).not.toContain('接下來')
  })

  it('取不到定義（404）：不顯示「接下來」，仍可用；警示條有「重試」，重試成功後補上接下來', async () => {
    freezeNow(NOW_EARLY)
    let fail = true
    openTrace({ [`GET ${VER}`]: () => (fail ? problem(500, 'INTERNAL_ERROR') : json(journeyVersion())) })
    await heading()
    expect(stepItems(await timeline())).toHaveLength(2)
    const alert = await screen.findByRole('alert')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await waitFor(async () => expect(stepItems(await timeline())).toHaveLength(4))
  })

  it('以 Enrollment 綁定的版本取定義，不是最新版', async () => {
    freezeNow(NOW_EARLY)
    const api = openTrace({
      [`GET ${DETAIL}`]: () => json(waiting({ journey_version: 2 })),
      [`GET /api/v1/journeys/01JCXYZJOURNEY0000000001/versions/2`]: () => json(journeyVersion({ version: 2 })),
    })
    await heading()
    expect(api.calls.some((c) => c.path.endsWith('/versions/2'))).toBe(true)
    expect(api.calls.some((c) => c.path === VER)).toBe(false)
    expect(EID).toBeTruthy()
  })
})

describe('訊息發送紀錄', () => {
  it('無資料：說明何時會出現（沒有表格）', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    const text = mainText()
    expect(text).toContain('還沒有發出任何訊息')
    expect(text).toContain('「發送訊息」步驟時')
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('有資料：欄位＝時間、哪一步、管道、結果；管道 MOCK 顯示測試管道；結果白話', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completed())
    await heading()
    const table = screen.getByRole('table')
    expect([...table.querySelectorAll('th')].map((t) => t.textContent)).toEqual(['時間', '哪一步', '管道', '結果'])
    const cells = [...table.querySelectorAll('tbody td')].map((td) => norm(td))
    expect(cells[0]).toContain('10/20（二）10:00')
    expect(cells[1]).toContain('發送訊息：提醒信')
    expect(cells[2]).toContain('測試管道（不會真的送出）')
    expect(cells[3]).toContain('已送出')
    expect(table.textContent).not.toMatch(/MOCK|ch_mock_1|tpl_1|dlv-1|idem-1/)
  })

  it('各結果文字與略過原因：等待發送／已送出／發送失敗／已取消／未發送＋原因', async () => {
    freezeNow(NOW_LATE)
    const reasons: [string, string][] = [
      ['CONSENT', '客戶未同意接收'], ['FREQUENCY_CAP', '超過發送次數上限'], ['QUIET_HOURS', '在免打擾時段'],
      ['PRIORITY_LOST', '被更優先的訊息取代'], ['BUDGET_EXCEEDED', '超出預算'], ['KILL_SWITCH', '發送功能已被暫停'],
    ]
    openTrace({}, completed({
      delivery_history: [
        delivery(1, { status: 'PENDING', delivery_id: null }), delivery(2), delivery(3, { status: 'FAILED', delivery_id: null }),
        delivery(4, { status: 'CANCELLED', delivery_id: null }),
        ...reasons.map(([r], i) => delivery(10 + i, { status: 'SKIPPED', skip_reason: r, delivery_id: null })),
      ],
    }))
    await heading()
    const text = screen.getByRole('table').textContent ?? ''
    for (const w of ['等待發送', '已送出', '發送失敗', '已取消', '未發送']) expect(text).toContain(w)
    for (const [code, zh] of reasons) {
      expect(text).toContain(zh)
      expect(text).not.toContain(code)
    }
  })

  it('「載入更早紀錄」帶 cursor 取下一頁並附加；窄螢幕表格 table.stack、td 有 data-label', async () => {
    freezeNow(NOW_LATE)
    const user = userEvent.setup()
    openTrace({
      [`GET ${DETAIL}/deliveries`]: () => json({ items: [delivery(2, { attempted_at: '2026-10-19T02:00:00Z' })], next_cursor: null }),
    }, completed({ delivery_history_next_cursor: 'DC1' }))
    await heading()
    const table = screen.getByRole('table')
    expect(table.classList.contains('stack')).toBe(true)
    for (const td of table.querySelectorAll('tbody td')) expect(td.getAttribute('data-label') ?? td.getAttribute('data-l')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '載入更早紀錄' }))
    await waitFor(() => expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(3))
    expect(screen.queryByRole('button', { name: '載入更早紀錄' })).toBeNull()
  })
})
