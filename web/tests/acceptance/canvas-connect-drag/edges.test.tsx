// @vitest-environment jsdom
/**
 * canvas-connect-drag：連線路徑與接點（docs/ui/canvas-interaction.md §1「路徑」）。jsdom 量測由 canvas-fit-fix 的 mockLayout 固定。
 * 目標在右＝曲線；目標在左（不同欄）＝繞行、不穿過卡片；同一欄＝從來源右側出、在欄外上下走、接目標上緣（目標在下）或下緣（目標在上）中點。
 * 假設見 helpers.tsx、node-card-unify/helpers.tsx（接點座標）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, editorBackend, loopDef, resetEnv, smallDef, teardown, waitFor } from '../journey-canvas-ui/fixtures'
import { anchorMismatches } from '../node-card-unify/helpers'
import { mockLayout, restoreLayout } from '../canvas-fit-fix/fixtures'
import { edgeEnds, edgePoints, openBare, pointsInsideCards, readBoxes } from './helpers'

beforeEach(() => { resetEnv(); mockLayout() })
afterEach(() => { cleanup(); teardown(); restoreLayout() })

const CARD = { w: 200, h: 120 }
/** smallDef 的座標，只讓條件與 VIP 訊息放同一欄（x 相同）：上下相對位置由 dy 決定 */
const colLayout = (condY: number, sendY: number) => ({
  nodes: {
    start: { x: 0, y: 0 }, src_1: { x: 272, y: 0 }, wait_1: { x: 544, y: 0 },
    cond_vip: { x: 816, y: condY }, send_vip: { x: 816, y: sendY }, end_1: { x: 1088, y: 0 },
  },
})
const ready = async (n = 6) => {
  await waitFor(() => expect(readBoxes().length).toBe(n))
  await waitFor(() => expect(document.querySelectorAll('[data-testid^="rf__edge-"] path.react-flow__edge-path').length).toBeGreaterThan(n - 3))
}
const endOf = (id: string) => edgeEnds().find((e) => e.id === `rf__edge-${id}`)!
const boxNamed = (s: string) => readBoxes().find((b) => b.name.includes(s))!

describe('一般連線（目標在右）', () => {
  it('每條線起點在來源卡右緣、終點在目標卡入口；多出口（條件）的「是」起點 y 與其他出口不同、仍在右緣', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await ready()
    expect(anchorMismatches(readBoxes(), 'editor')).toEqual([])
  })

  it('條件「是」「否」兩條線都接好時，兩個起點在右緣的不同高度（各出口各一點）', async () => {
    const def = smallDef()
    def.nodes = def.nodes.map((n) => (n.id === 'cond_vip' ? { ...n, outputs: { yes: 'send_vip', no: 'end_1' } } : n))
    editorBackend({ def, revision: 1 })
    await openBare()
    await ready()
    await waitFor(() => expect(edgeEnds().some((e) => e.id === 'rf__edge-cond_vip:no')).toBe(true))
    const yes = endOf('cond_vip:yes')
    const no = endOf('cond_vip:no')
    expect(yes.start[0]).toBeCloseTo(no.start[0], 0)
    expect(Math.abs(yes.start[1] - no.start[1]), '兩個出口的起點 y 要不同').toBeGreaterThanOrEqual(20)
    expect(anchorMismatches(readBoxes(), 'editor')).toEqual([])
  })
})

describe('目標在左（迴圈回頭線，不同欄）', () => {
  it('繞行：終點在目標卡左緣中點，路徑上沒有座標點落在任何卡片內', async () => {
    editorBackend({ def: loopDef(), revision: 1 })
    await openBare()
    await ready(5)
    const back = endOf('send_again:sent')
    const target = boxNamed('等結帳 24h')
    expect(back.end[0]).toBeCloseTo(target.x, 0)
    expect(back.end[1]).toBeCloseTo(target.y + CARD.h / 2, 0)
    expect(pointsInsideCards(edgePoints('send_again:sent'))).toEqual([])
    expect(anchorMismatches(readBoxes(), 'editor')).toEqual([])
  })
})

describe('同一欄（往下）', () => {
  it('接目標「上緣」中點、從來源右側出，路徑在欄外（右側 >= 16px），不進左側欄間空隙，不穿過卡片', async () => {
    editorBackend({ def: smallDef(), layout: colLayout(0, 300), revision: 1 })
    await openBare()
    await ready()
    const cond = boxNamed('是 VIP？')
    const send = boxNamed('VIP 專屬關心')
    const e = endOf('cond_vip:yes')
    expect(e.start[0]).toBeCloseTo(cond.x + CARD.w, 0)
    expect(e.end[0], '終點 x＝目標卡中線').toBeCloseTo(send.x + CARD.w / 2, 0)
    expect(e.end[1], '終點 y＝目標卡上緣').toBeCloseTo(send.y, 0)
    const pts = edgePoints('cond_vip:yes')
    expect(Math.max(...pts.map((p) => p[0])), '路徑要在欄外右側走').toBeGreaterThanOrEqual(cond.x + CARD.w + 16)
    expect(Math.min(...pts.map((p) => p[0])), '不進兩欄之間的空隙').toBeGreaterThanOrEqual(cond.x - 0.5)
    expect(pointsInsideCards(pts)).toEqual([])
    expect(anchorMismatches(readBoxes(), 'editor')).toEqual([])
  })
})

describe('同一欄（往上）', () => {
  it('接目標「下緣」中點，其餘同往下', async () => {
    editorBackend({ def: smallDef(), layout: colLayout(300, 0), revision: 1 })
    await openBare()
    await ready()
    const cond = boxNamed('是 VIP？')
    const send = boxNamed('VIP 專屬關心')
    const e = endOf('cond_vip:yes')
    expect(e.start[0]).toBeCloseTo(cond.x + CARD.w, 0)
    expect(e.end[0]).toBeCloseTo(send.x + CARD.w / 2, 0)
    expect(e.end[1], '終點 y＝目標卡下緣').toBeCloseTo(send.y + CARD.h, 0)
    const pts = edgePoints('cond_vip:yes')
    expect(Math.max(...pts.map((p) => p[0]))).toBeGreaterThanOrEqual(cond.x + CARD.w + 16)
    expect(Math.min(...pts.map((p) => p[0]))).toBeGreaterThanOrEqual(cond.x - 0.5)
    expect(pointsInsideCards(pts)).toEqual([])
    expect(anchorMismatches(readBoxes(), 'editor')).toEqual([])
  })

  it('不跟既有連線共用箭頭：同欄那條線的終點與其他線的終點不重合', async () => {
    editorBackend({ def: smallDef(), layout: colLayout(300, 0), revision: 1 })
    await openBare()
    await ready()
    const mine = endOf('cond_vip:yes')
    for (const o of edgeEnds().filter((x) => x.id !== mine.id)) {
      expect(Math.hypot(o.end[0] - mine.end[0], o.end[1] - mine.end[1]), `與 ${o.id} 終點重合`).toBeGreaterThan(4)
    }
  })
})
