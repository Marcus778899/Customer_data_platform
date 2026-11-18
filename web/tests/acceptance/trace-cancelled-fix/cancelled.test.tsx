// @vitest-environment jsdom
/**
 * trace-cancelled-fix 驗收測試（uat v0.3.0 第四輪 F-29；qa 撰寫，worker 不可改）。
 * 依據：journey-engine.md §4.1（Delivery.status = CANCELLED）、journeys.md §3.5（停止旅程取消未送出的發送）、
 *       domain/rules.md「停止」、spec 05 驗收（緊急停止後可查出幾則訊息被取消）、enrollment-trace.md（摘要組句、訊息發送紀錄）。
 *
 * 假設（規格沒寫死的文案，測試定下來）：
 *   - 有 CANCELLED 的發送紀錄時，摘要「訊息有送出嗎？」、時間軸的發送步驟說明，都要同時出現「取消」與「停止」
 *     （讓客服看得出是旅程被停止而取消）；摘要寫出被取消的則數（「N 則」）。
 *   - 走過發送步驟、但 delivery_history 一則都沒有（終態）：資料不足以判斷，畫面不可寫「沒有發送過訊息」
 *     「還沒有發出任何訊息…走到發送訊息步驟時會出現」「這一步已處理完成」這類與事實矛盾的說法。
 *   - 沒走過發送步驟的情境（既有驗收）不受影響，這裡不重測。
 */
import { cleanup, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  aborted, cardText, completed, delivery, freezeNow, heading, mainText, NOW_LATE, norm, openTrace, resetEnv, stepItems, teardown,
  timeline, traceEntry, unfreeze,
} from '../trace-plain-rewrite/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown(); unfreeze() })

const cancelled = (i: number, over: Record<string, unknown> = {}) =>
  delivery(i, { status: 'CANCELLED', delivery_id: null, ...over })

/** 走完且訊息被取消：旅程在訊息送出前被停止 */
const completedCancelled = (n = 1) =>
  completed({ delivery_history: Array.from({ length: n }, (_, i) => cancelled(i + 1, { attempted_at: `2026-10-20T02:00:0${5 + i}Z` })) })

/** 已中止、停在發送步驟，訊息被取消 */
const abortedAtSend = () => {
  const base = completed()
  return aborted({
    current_node_id: 'send_1', current_node_type: 'SendMessageNode', current_node_name: '提醒信',
    trace: [base.trace[0], base.trace[1], traceEntry(3, 'send_1', 'SendMessageNode', '提醒信', { exited_at: null, out_port: null, decision_reason: '已排入發送。' })],
    delivery_history: [cancelled(1)],
  })
}

const FORBIDDEN = ['這個旅程沒有發送過訊息', '還沒有發出任何訊息', '這一步已處理完成', '還沒走到']

async function sendStep(): Promise<string> {
  const ol = await timeline()
  const li = stepItems(ol).find((x) => norm(x).includes('發送訊息：提醒信'))
  if (!li) throw new Error('找不到發送訊息步驟')
  return norm(li)
}

describe('F-29：被取消的訊息要看得出是因停止而取消', () => {
  it('走完＋CANCELLED：摘要說沒有送出、被取消、旅程被停止，不出現「沒有發送過訊息」', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completedCancelled())
    await heading()
    const t = cardText()
    expect(t).toContain('訊息有送出嗎？')
    expect(t).toContain('沒有')
    expect(t).toContain('取消')
    expect(t).toContain('停止')
    expect(t).toContain('1 則')
    for (const w of FORBIDDEN) expect(t).not.toContain(w)
    expect(t).not.toMatch(/有。/)
  })

  it('走完＋CANCELLED：發送步驟的說明是「取消、沒有送出、旅程被停止」，不是「已處理完成」', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completedCancelled())
    await heading()
    const s = await sendStep()
    expect(s).toContain('取消')
    expect(s).toContain('停止')
    expect(s).toMatch(/沒有送出|未送出|沒有發出/)
    expect(s).not.toContain('這一步已處理完成')
  })

  it('走完＋CANCELLED：訊息發送紀錄有一列、結果「已取消」，沒有「還沒有發出任何訊息」空狀態', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completedCancelled())
    await heading()
    const table = screen.getByRole('table')
    const cells = [...table.querySelectorAll('tbody td')].map((td) => norm(td))
    expect(cells).toHaveLength(4)
    expect(cells[1]).toContain('發送訊息：提醒信')
    expect(cells[3]).toContain('已取消')
    expect(mainText()).not.toContain('還沒有發出任何訊息')
  })

  it('多則被取消：摘要寫出則數（spec 05：可查出幾則訊息被取消）', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completedCancelled(3))
    await heading()
    const t = cardText()
    expect(t).toContain('3 則')
    expect(t).toContain('取消')
  })

  it('已中止＋CANCELLED：摘要說明訊息被取消、沒有送出，不只寫「沒有，之後也不會送出」而缺少取消', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, abortedAtSend())
    await heading()
    const t = cardText()
    expect(t).toContain('進度已中止')
    expect(t).toContain('取消')
    expect(t).toContain('1 則')
    expect(t).toContain('沒有')
    for (const w of FORBIDDEN) expect(t).not.toContain(w)
    const s = await sendStep()
    expect(s).toContain('取消')
    expect(mainText()).not.toContain('還沒有發出任何訊息')
  })

  it('已送出 1 則＋被取消 1 則：兩者都要交代，不能只講已送出', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completed({ delivery_history: [cancelled(2, { attempted_at: '2026-10-20T02:00:06Z' }), delivery(1)] }))
    await heading()
    const t = cardText()
    expect(t).toContain('已於')
    expect(t).toContain('送出')
    expect(t).toContain('取消')
    expect(t).toContain('1 則')
  })
})

describe('F-29：走過發送步驟卻查不到任何發送紀錄（資料不足）時，不可寫成矛盾說法', () => {
  it('走完、走過發送步驟、delivery_history 為空：摘要與發送紀錄區不寫「沒有發送過」「還沒有發出」，步驟不寫「已處理完成」', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completed({ delivery_history: [] }))
    await heading()
    const text = `${cardText()} ${mainText()}`
    for (const w of FORBIDDEN) expect(text).not.toContain(w)
    expect(text).not.toContain('走到「發送訊息」步驟時')
    const s = await sendStep()
    expect(s).not.toContain('處理完成')
  })
})
