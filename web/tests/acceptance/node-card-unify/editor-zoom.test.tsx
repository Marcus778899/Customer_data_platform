// @vitest-environment jsdom
/**
 * node-card-unify：編輯畫布的初始縮放（docs/ui/canvas-interaction.md §2，與唯讀圖同一條規則）。
 * 整張放得下 → 100% 置中；放不下 → 不低於 75%、右緣只露出某張卡的色條而不切在文字中間，右緣有 48px 漸層（`.cvfade`，看全圖後消失）；
 * 375 寬的編輯畫布初始捲到主幹列（「開始」那一列置中），不留上方大片空白。
 * 容器大小用 canvas-fit-fix/fixtures 的 BOX（預設 1000x600）；進入點與工具列假設同 journey-canvas-ui/fixtures。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, editorBackend, openEditor, resetEnv, screen, skeleton, teardown, userEvent, waitFor, type Def } from '../journey-canvas-ui/fixtures'
import { BOX, mockLayout, nodeRects, restoreLayout, viewport } from '../canvas-fit-fix/fixtures'
import { fadeVisible } from './helpers'

const ORIG = { ...BOX }
beforeEach(() => {
  resetEnv()
  mockLayout()
})
afterEach(() => {
  cleanup()
  teardown()
  restoreLayout()
  BOX.w = ORIG.w
  BOX.h = ORIG.h
})

const node = (id: string, type: string, outputs: Record<string, unknown>, name: string | null, config: Record<string, unknown> = {}) => ({ id, type, name, config, outputs })
const msg = { channel_id: 'mock_default', template_id: 't' }

const chain3 = (): Def => ({
  name: '短', description: null, settings: {}, entry_node: 'start',
  nodes: [
    node('start', 'StartNode', { next: 'src_1' }, null),
    node('src_1', 'EventSourceNode', { next: 'e1' }, '進入', { event_name: 'cart_item_added' }),
    node('e1', 'EndNode', {}, '結束', {}),
  ],
})
const scene = (): Def => ({
  name: '場景', description: null, settings: {}, entry_node: 'start',
  nodes: [
    node('start', 'StartNode', { next: 'src_1' }, null),
    node('src_1', 'EventSourceNode', { next: 'cond_1' }, '進入', { event_name: 'cart_item_added' }),
    node('cond_1', 'ConditionNode', { yes: 'a1', no: 'b1' }, '條件'),
    node('a1', 'SendMessageNode', { sent: 'a2' }, '甲1', msg),
    node('a2', 'SendMessageNode', { sent: 'm1' }, '甲2', msg),
    node('b1', 'SendMessageNode', { sent: 'b2' }, '乙1', msg),
    node('b2', 'SendMessageNode', { sent: 'm1' }, '乙2', msg),
    node('m1', 'MergeNode', { next: 'w1' }, '合流'),
    node('w1', 'WaitDelayNode', { next: 'we1' }, '等一天', { duration: 'P1D' }),
    node('we1', 'WaitForEventNode', { occurred: 'e1', timeout: 'e1' }, '等結帳', { event_name: 'order_completed', timeout: 'P1D' }),
    node('e1', 'EndNode', {}, '結束', {}),
  ],
})
const fan = (): Def => ({
  name: '扇出', description: null, settings: {}, entry_node: 'start',
  nodes: [
    node('start', 'StartNode', { next: 'c1' }, null),
    node('c1', 'ConditionNode', { yes: 'e1', no: 'c2' }, '條件1'),
    node('c2', 'ConditionNode', { yes: 'e2', no: 'c3' }, '條件2'),
    node('c3', 'ConditionNode', { yes: 'e3', no: 'e4' }, '條件3'),
    node('e1', 'EndNode', {}, '結束甲', {}),
    node('e2', 'EndNode', {}, '結束乙', {}),
    node('e3', 'EndNode', {}, '結束丙', {}),
    node('e4', 'EndNode', {}, '結束丁', {}),
  ],
})

const ready = async () => {
  await waitFor(() => expect(nodeRects().length).toBeGreaterThan(0))
  await new Promise((r) => setTimeout(r, 400))
}
/** 容器右緣（BOX.w）切在卡片文字中間的卡（色條與邊框之後、卡右緣之前） */
const cutNodes = (z: number) => nodeRects().filter((r) => BOX.w > r.x + 17 * z && BOX.w < r.x + r.w - 0.5).map((r) => r.name)

describe('整張放得下：100% 置中', () => {
  it('單一節點：縮放 100%，卡片在畫布中央', async () => {
    editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    await ready()
    expect(viewport().zoom).toBeCloseTo(1, 2)
    const [r] = nodeRects()
    expect(Math.abs(r.x + r.w / 2 - BOX.w / 2), '水平置中').toBeLessThanOrEqual(2)
  }, 60000)

  it('3 個節點（約 700px 寬）：縮放 100%，左右留白相等，沒有右緣漸層', async () => {
    editorBackend({ def: chain3(), revision: 1 })
    await openEditor()
    await ready()
    expect(viewport().zoom).toBeCloseTo(1, 2)
    const rs = nodeRects()
    const l = Math.min(...rs.map((r) => r.x))
    const r = BOX.w - Math.max(...rs.map((x) => x.x + x.w))
    expect(l, '前置條件：放得下').toBeGreaterThanOrEqual(0)
    expect(Math.abs(l - r), `左右留白 ${l}／${r}`).toBeLessThanOrEqual(2)
    expect(fadeVisible(), '整張放得下沒有右緣漸層').toBe(false)
  }, 60000)
})

describe('放不下：不低於 75%、右緣不切在文字中間、有漸層', () => {
  it('11 個節點進頁：縮放 75%～100%，從開始靠左；右緣不切卡片文字；右緣漸層可見', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    const z = viewport().zoom
    expect(z).toBeGreaterThanOrEqual(0.75 - 0.005)
    expect(z).toBeLessThanOrEqual(1 + 0.005)
    const rs = nodeRects()
    expect(Math.max(...rs.map((r) => r.x + r.w)), '前置條件：圖比畫布寬').toBeGreaterThan(BOX.w)
    expect(Math.min(...rs.map((r) => r.x)), '開始靠左').toBeLessThanOrEqual(60)
    expect(cutNodes(z), `右緣 ${BOX.w} 切在這些卡的文字中間`).toEqual([])
    expect(fadeVisible(), '右緣要有漸層').toBe(true)
    expect(document.querySelector('.cvfade'), '編輯畫布的漸層用 .cvfade').toBeTruthy()
  }, 60000)

  it('按「看全圖」後整張都在畫布內，右緣漸層消失', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    await userEvent.click(screen.getByRole('button', { name: '看全圖' }))
    await waitFor(() => expect(Math.max(...nodeRects().map((r) => r.x + r.w))).toBeLessThanOrEqual(BOX.w + 0.5))
    await waitFor(() => expect(fadeVisible()).toBe(false))
  }, 60000)
})

describe('375 寬編輯畫布捲到主幹列', () => {
  it('高的圖（四個結束疊在右側）：「開始」那一列在畫布垂直中央附近，不留上方大片空白', async () => {
    BOX.w = 343
    BOX.h = 300
    editorBackend({ def: fan(), revision: 1 })
    await openEditor()
    await ready()
    expect(viewport().zoom).toBeGreaterThanOrEqual(0.75 - 0.005)
    const rs = nodeRects()
    const start = rs.reduce((a, b) => (b.x < a.x ? b : a))
    const cy = start.y + start.h / 2
    expect(start.x, '開始節點在畫布內靠左').toBeGreaterThanOrEqual(-0.5)
    expect(Math.abs(cy - BOX.h / 2), `開始列中心 ${Math.round(cy)}，畫布中心 ${BOX.h / 2}`).toBeLessThanOrEqual(45)
    expect(cutNodes(viewport().zoom)).toEqual([])
  }, 60000)
})
