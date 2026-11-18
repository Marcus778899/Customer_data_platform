// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { BOX, mockLayout, nodeRects, restoreLayout } from '../../tests/acceptance/canvas-fit-fix/fixtures'
import { cleanup, editorBackend, nodeButtons, openEditor, resetEnv, selectNode, teardown, userEvent, waitFor, type Def } from '../../tests/acceptance/journey-canvas-ui/fixtures'

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
const wide = (): Def => ({
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

describe('Esc 關閉面板並取消選取', () => {
  it('選取節點後按 Esc：面板關閉、節點不再選取', async () => {
    editorBackend()
    await openEditor()
    await selectNode('等 1 小時')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(nodeButtons().every((b) => b.getAttribute('aria-pressed') !== 'true')).toBe(true))
  })
})

describe('小地圖只在面板展開時扣面板寬', () => {
  it('畫布 1700 寬、圖約 1540 寬、面板關閉：全圖可見，沒有小地圖；選取節點開面板後才出現', async () => {
    BOX.w = 1700
    editorBackend({ def: wide(), revision: 1 })
    await openEditor()
    await waitFor(() => expect(nodeRects().length).toBeGreaterThan(0))
    await new Promise((r) => setTimeout(r, 300))
    expect(document.querySelector('.react-flow__minimap')).toBeNull()
    await selectNode('結束')
    await waitFor(() => expect(document.querySelector('.react-flow__minimap')).toBeTruthy())
  }, 60000)
})
