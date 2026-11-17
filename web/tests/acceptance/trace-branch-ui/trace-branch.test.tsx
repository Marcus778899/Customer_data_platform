// @vitest-environment jsdom
/** trace-branch-ui：圖形軌跡的分支與迴圈（journey-engine §4.5；設計稿 tr-branch）。假設見 fixtures.tsx。 */
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  API, ENR, EID, backend, bodyText, card, edgeHas, edgesRendered, enrollmentDetail, json, loopEnrollment, norm, openTrace,
  resetEnv, screen, start, teardown, traceEntry, waitFor,
} from './fixtures'
import { journeyVersion } from '../journey-customer-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const open = () => card(/等結帳 24h/)
const edgesReady = () => waitFor(() => expect(edgesRendered()).toBe(true))
const steps = async () => {
  const h = await screen.findByRole('heading', { name: '走過的步驟' })
  let el: HTMLElement | null = h
  while (el && el !== document.body) {
    const ol = [...el.querySelectorAll('ol')].find((o) => !o.closest('nav'))
    if (ol) return [...ol.querySelectorAll(':scope > li')].map(norm)
    el = el.parentElement
  }
  throw new Error('找不到時間軸')
}

describe('分支：走過與沒走的出口分明', () => {
  it('節點三態：走「否」那條的節點已經過、VIP 那條未經過、目前在等結帳', async () => {
    openTrace()
    const text = async (n: RegExp) => `${(await card(n)).getAttribute('aria-label') ?? ''} ${norm(await card(n))}`
    expect(await text(/一般提醒/)).toContain('已經過')
    expect(await text(/合流/)).toContain('已經過')
    expect(await text(/VIP 專屬關心/)).toContain('未經過')
    expect(await text(/結帳完成/)).toContain('未經過')
    expect(await text(/等結帳 24h/)).toContain('目前位置')
  })

  it('連線：走過的出口 taken、同一分流沒走的出口 skipped；開始 → 第一個節點視為走過', async () => {
    openTrace()
    await open()
    await edgesReady()
    for (const id of ['start:next', 'src_1:next', 'wait_1:next', 'cond_vip:no', 'send_normal:sent', 'merge_1:next', 'wait_ev:timeout', 'send_last:sent']) {
      expect(edgeHas(id, 'taken'), `${id} 應為走過`).toBe(true)
      expect(edgeHas(id, 'skipped'), `${id} 不該同時是沒走`).toBe(false)
    }
    for (const id of ['cond_vip:yes', 'send_vip:sent', 'wait_ev:occurred']) {
      expect(edgeHas(id, 'skipped'), `${id} 應為沒走`).toBe(true)
      expect(edgeHas(id, 'taken'), `${id} 不該是走過`).toBe(false)
    }
  })

  it('軌跡與定義的連線對不上（out_port 的目標不是下一筆）：該邊不畫成走過，不推測', async () => {
    openTrace(loopEnrollment({
      current_node_id: 'send_normal',
      trace: [
        traceEntry(1, 'src_1', 'EventSourceNode', '加入購物車'),
        traceEntry(2, 'wait_1', 'WaitDelayNode', '等 1 小時', { out_port: 'next' }),
        traceEntry(3, 'send_normal', 'SendMessageNode', '一般提醒', { exited_at: null, out_port: null }),
      ],
    }))
    await open()
    await edgesReady()
    expect(edgeHas('src_1:next', 'taken')).toBe(true)
    expect(edgeHas('wait_1:next', 'taken')).toBe(false)
  })

  it('另一條出口：走「是」並達標時結果相反', async () => {
    openTrace(loopEnrollment({
      status: 'GOAL_MET', current_node_id: 'goal_1', current_node_type: 'GoalNode', current_node_name: '結帳完成', terminated_at: '2026-10-19T02:00:00Z',
      trace: [
        traceEntry(1, 'src_1', 'EventSourceNode', '加入購物車'),
        traceEntry(2, 'wait_1', 'WaitDelayNode', '等 1 小時'),
        traceEntry(3, 'cond_vip', 'ConditionNode', '是 VIP？', { out_port: 'yes' }),
        traceEntry(4, 'send_vip', 'SendMessageNode', 'VIP 專屬關心', { out_port: 'sent' }),
        traceEntry(5, 'merge_1', 'MergeNode', '合流'),
        traceEntry(6, 'wait_ev', 'WaitForEventNode', '等結帳 24h', { out_port: 'occurred' }),
        traceEntry(7, 'goal_1', 'GoalNode', '結帳完成', { exited_at: '2026-10-19T02:00:00Z', out_port: null }),
      ],
    }))
    await open()
    await edgesReady()
    expect(edgeHas('cond_vip:yes', 'taken')).toBe(true)
    expect(edgeHas('cond_vip:no', 'skipped')).toBe(true)
    expect(edgeHas('wait_ev:occurred', 'taken')).toBe(true)
    expect(edgeHas('wait_ev:timeout', 'skipped')).toBe(true)
    expect(norm(await card(/一般提醒/))).not.toMatch(/已經過/)
  })
})

describe('迴圈：經過次數與「再等一輪」', () => {
  it('迴圈的回邊是點狀（back）且走過；圖上有「再等一輪」', async () => {
    openTrace()
    await open()
    await edgesReady()
    // 迴圈由「等結帳 → 再次提醒 → 等結帳」兩條邊組成，排版決定哪一條指回左邊；至少一條是回邊，且兩條都走過
    expect(edgeHas('send_last:sent', 'back') || edgeHas('wait_ev:timeout', 'back')).toBe(true)
    expect(edgeHas('send_last:sent', 'taken')).toBe(true)
    expect(edgeHas('wait_ev:timeout', 'taken')).toBe(true)
    expect(bodyText()).toContain('再等一輪')
  })

  it('目前節點為第 2 次進入：卡片含「第 2 次」且仍標目前位置；只經過 1 次的節點不含次數', async () => {
    openTrace()
    const ev = norm(await open())
    expect(ev).toMatch(/第\s*2\s*次/)
    expect(ev).toContain('目前位置')
    for (const n of [/加入購物車/, /一般提醒/, /再次提醒/, /合流/]) {
      expect(norm(await card(n))).not.toMatch(/次經過|第\s*1\s*次/)
    }
  })

  it('已離開且繞過 2 次的節點（已結束的進度）：卡片標出經過次數', async () => {
    openTrace(loopEnrollment({
      status: 'GOAL_MET', current_node_id: 'goal_1', current_node_type: 'GoalNode', current_node_name: '結帳完成', terminated_at: '2026-10-22T02:00:00Z',
      trace: [
        ...loopEnrollment().trace.slice(0, 7),
        traceEntry(8, 'wait_ev', 'WaitForEventNode', '等結帳 24h', { out_port: 'occurred' }),
        traceEntry(9, 'goal_1', 'GoalNode', '結帳完成', { exited_at: '2026-10-22T02:00:00Z', out_port: null }),
      ],
    }))
    const t = norm(await open())
    expect(t).toMatch(/第\s*2\s*次|2\s*次/)
    expect(t).toContain('已經過')
    expect(t).not.toContain('目前位置')
    await waitFor(() => expect(edgeHas('wait_ev:occurred', 'taken')).toBe(true))
  })

  it('時間軸：同一節點每次經過各一列，標「第 1 次經過」「第 2 次經過」；只出現 1 次的不標', async () => {
    openTrace()
    const items = await steps()
    const ev = items.filter((t) => t.includes('等結帳 24h'))
    expect(ev.length).toBeGreaterThanOrEqual(2)
    const joined = ev.join(' | ')
    expect(joined).toContain('第 1 次經過')
    expect(joined).toContain('第 2 次經過')
    const once = items.filter((t) => /一般提醒|再次提醒|合流/.test(t))
    expect(once.length).toBeGreaterThan(0)
    for (const t of once) expect(t).not.toContain('次經過')
  })
})

describe('直線旅程不受影響', () => {
  it('直線軌跡：沒有「再等一輪」、沒有「次經過」、沒有回邊、不打 node-stats', async () => {
    const api = backend({
      [`GET ${ENR}`]: () => json(enrollmentDetail()),
      [`GET ${API}/versions/3`]: () => json(journeyVersion(3)),
    })
    start(`/enrollments/${EID}`)
    await card(/等 3 天/)
    await edgesReady()
    expect(bodyText()).not.toMatch(/再等一輪|次經過/)
    expect(document.querySelector('[data-testid^="rf__edge-"] .back, [data-testid^="rf__edge-"].back')).toBeNull()
    expect(api.calls.some((c) => c.path.endsWith('/node-stats'))).toBe(false)
  })
})
