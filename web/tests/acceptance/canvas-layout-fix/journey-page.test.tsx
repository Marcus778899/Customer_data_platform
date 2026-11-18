// @vitest-environment jsdom
/**
 * canvas-layout-fix：旅程頁（F-6 節點白話名、F-7 整張圖進一屏、F-11 分支畫成分叉）與列表（F-5）。
 * 進入點、網址、mock 同 journey-customer-ui（App、/journeys/<id>）。jsdom 不套 CSS 也沒有版面尺寸，
 * 圖的位置讀 @xyflow/react 節點的 transform，縮放讀 viewport 的 transform scale。
 */
import { cleanup, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { JID, backend, bodyText, gotoNav, journeyDetail, journeySummary, journeyVersion, json, page, resetEnv, start, teardown } from '../journey-customer-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const node = (id: string, type: string, outputs: Record<string, unknown>, name: string | null, config: Record<string, unknown> = {}) =>
  ({ id, type, name, config, outputs })

async function openJourney(nodes: unknown[], over: Record<string, unknown> = {}) {
  backend({
    [`GET /api/v1/journeys/${JID}`]: () => json(journeyDetail({ latest_version: 1, versions: [{ version: 1, published_at: '2026-10-18T01:12:00Z' }] })),
    [`GET /api/v1/journeys/${JID}/versions/1`]: () => json(journeyVersion(1, 'x', { nodes, layout: null, ...over })),
  })
  start(`/journeys/${JID}`)
  await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
  await waitFor(() => expect(document.querySelectorAll('.react-flow__node').length).toBe(nodes.length))
}

const cardOf = (text: RegExp) => {
  const el = [...document.querySelectorAll<HTMLElement>('.react-flow__node')].find((n) => text.test(n.textContent ?? ''))
  if (!el) throw new Error(`找不到節點卡 ${text}`)
  return el
}
const translate = (el: HTMLElement) => {
  const m = /translate\(\s*(-?[\d.]+)px,\s*(-?[\d.]+)px\)/.exec(el.style.transform)
  if (!m) throw new Error(`節點沒有 transform：${el.style.transform}`)
  return { x: Number(m[1]), y: Number(m[2]) }
}
const scaleOf = (el: Element | null) => {
  const m = el && /scale\(\s*([\d.]+)/.exec((el as HTMLElement).style.transform)
  return m ? Number(m[1]) : 1
}

describe('F-5 旅程列表', () => {
  it('瀏覽旅程進度的連結叫「旅程進度」，不再出現 Enrollment（D-079）', async () => {
    backend({ 'GET /api/v1/journeys': () => json(page([journeySummary()])) })
    start('/')
    await gotoNav('旅程')
    await screen.findByRole('link', { name: '棄單挽回' })
    const link = [...document.querySelectorAll<HTMLAnchorElement>('main a[href="/enrollments"]')]
    expect(link.length).toBe(1)
    expect(link[0].textContent).toMatch(/旅程進度/)
    expect(link[0].textContent).not.toMatch(/Enrollment/i)
    expect(document.querySelector('main')!.textContent).not.toMatch(/Enrollment/i)
  })
})

describe('F-6 旅程頁節點卡用白話名', () => {
  it('沒有名稱的開始、合流節點不顯示 StartNode／MergeNode 程式名', async () => {
    await openJourney([
      node('start', 'StartNode', { next: 'src_1' }, null),
      node('src_1', 'EventSourceNode', { next: 'cond_1' }, '加入購物車', { event_name: 'cart_item_added' }),
      node('cond_1', 'ConditionNode', { yes: 'merge_1', no: 'merge_1' }, '是 VIP？'),
      node('merge_1', 'MergeNode', { next: 'end_1' }, null),
      node('end_1', 'EndNode', {}, '結束'),
    ])
    const cards = [...document.querySelectorAll<HTMLElement>('.react-flow__node')].map((n) => n.textContent ?? '')
    expect(cards.join('｜')).not.toMatch(/StartNode|MergeNode|EventSourceNode|ConditionNode|EndNode/)
    expect(cards.some((t) => /開始/.test(t))).toBe(true)
    expect(cards.some((t) => /合流/.test(t))).toBe(true)
  })
})

describe('F-11 分支畫成分叉、F-7 整張圖進一屏', () => {
  const branching = (ranks: number) => {
    // start → src → cond →(是)→ 甲信… → merge；(否)→ 乙信… → merge → end
    const mid = Math.max(ranks - 5, 1)
    const chain = (p: string, label: string, next: string) =>
      Array.from({ length: mid }, (_, i) => node(`${p}${i + 1}`, 'SendMessageNode', { sent: i + 1 < mid ? `${p}${i + 2}` : next }, `${label}信${i + 1}`))
    return [
      node('start', 'StartNode', { next: 'src_1' }, '開始'),
      node('src_1', 'EventSourceNode', { next: 'cond_1' }, '加入購物車', { event_name: 'cart_item_added' }),
      node('cond_1', 'ConditionNode', { yes: 's1', no: 't1' }, '是 VIP？'),
      ...chain('s', '甲', 'merge_1'),
      ...chain('t', '乙', 'merge_1'),
      node('merge_1', 'MergeNode', { next: 'end_1' }, '會合'),
      node('end_1', 'EndNode', {}, '結束'),
    ]
  }

  it('條件的是／否兩路在不同列（不是一整列）；每條線由左到右', async () => {
    await openJourney(branching(6))
    const yes = translate(cardOf(/甲信1/))
    const no = translate(cardOf(/乙信1/))
    const cond = translate(cardOf(/是 VIP？/))
    expect(yes.x).toBe(no.x)
    expect(Math.abs(yes.y - no.y)).toBeGreaterThanOrEqual(72)
    expect(yes.x).toBeGreaterThan(cond.x)
    expect(translate(cardOf(/會合/)).x).toBeGreaterThan(yes.x)
  })

  it('8 層的場景一規模：圖寬（含縮放）不超過 1440 寬扣掉側欄與邊距的可用寬度（約 1160px）', async () => {
    await openJourney(branching(8))
    const inner = document.querySelector<HTMLElement>('.graph .inner')!
    const width = Number.parseFloat(inner.style.width)
    expect(width).toBeGreaterThan(0)
    const scale = scaleOf(document.querySelector('.graph .react-flow__viewport')) * scaleOf(inner)
    const zoom = Number.parseFloat(inner.style.zoom || '1') || 1
    expect(width * scale * zoom, `圖寬 ${width}、縮放 ${scale * zoom}`).toBeLessThanOrEqual(1160)
    expect(bodyText()).toContain('棄單挽回')
  })
})
