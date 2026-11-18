// @vitest-environment jsdom
/**
 * canvas-fit-fix：uat v0.3.0 第三輪 F-19～F-21。編輯畫布在「符合視窗大小」、新增節點、接線後，
 * 整張圖的每個節點都要落在畫布容器內（容器尺寸由 fixtures.mockLayout 固定為 1000x600），
 * 且不被小地圖（右下）與縮放列（左下）蓋住。進入點、工具列、右側面板假設同 journey-canvas-ui/fixtures。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  addNode, chooseLabel, cleanup, editorBackend, openEditor, rename, resetEnv, screen, selectNode, skeleton, teardown, userEvent, waitFor,
  type Def,
} from '../journey-canvas-ui/fixtures'
import { BOX, intersects, mockLayout, nodeRects, outsideBox, restoreLayout, viewport } from './fixtures'

beforeEach(() => {
  resetEnv()
  mockLayout()
})
afterEach(() => {
  cleanup()
  teardown()
  restoreLayout()
})

const node = (id: string, type: string, outputs: Record<string, unknown>, name: string | null, config: Record<string, unknown> = {}) => ({
  id, type, name, config, outputs,
})
const msg = { channel_id: 'mock_default', template_id: 't' }

/** 縮減版場景一規模：條件分兩路各兩封訊息、合流、等待、等事件、結束，共 11 個節點、8 層 */
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

/** 最後一層有四個結束疊在右側：圖高、靠右下角（小地圖位置） */
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

const inView = async (why: string) =>
  waitFor(
    () => {
      const rs = nodeRects()
      expect(rs.length, '畫布上要有節點').toBeGreaterThan(0)
      expect(outsideBox(rs), `${why}：這些節點在畫布 ${BOX.w}x${BOX.h} 之外（縮放 ${viewport().zoom}）`).toEqual([])
    },
    { timeout: 4000 },
  )

describe('F-19 「符合視窗大小」要真的把整張圖縮進畫布', () => {
  it('放大、離開原位後按「符合視窗大小」：所有節點回到畫布內', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await waitFor(() => expect(nodeRects().length).toBeGreaterThan(0))
    const zin = screen.getByRole('button', { name: '放大' })
    for (let i = 0; i < 4; i++) await userEvent.click(zin)
    await waitFor(() => expect(outsideBox(nodeRects()).length, '放大後應有節點跑出畫布（前置條件）').toBeGreaterThan(0))
    await userEvent.click(screen.getByRole('button', { name: '符合視窗大小' }))
    await inView('按符合視窗大小後')
  }, 60000)

  it('縮小後按「符合視窗大小」：縮放比縮小時更大（填滿畫布，不停在縮小的狀態）', async () => {
    editorBackend({ def: fan(), revision: 1 })
    await openEditor()
    await waitFor(() => expect(nodeRects().length).toBeGreaterThan(0))
    const zout = screen.getByRole('button', { name: '縮小' })
    for (let i = 0; i < 4; i++) await userEvent.click(zout)
    const small = viewport().zoom
    await userEvent.click(screen.getByRole('button', { name: '符合視窗大小' }))
    await waitFor(() => expect(viewport().zoom).toBeGreaterThan(small * 1.5))
    await inView('按符合視窗大小後')
  }, 60000)
})

describe('F-19 新增節點、接線後自動符合：新節點與其他節點都在畫布內', () => {
  it('依序加 7 個節點，每加一個整張圖都在畫布內', async () => {
    editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    for (const label of ['條件', '訊息', '訊息', '合流', '結束', '等待', '等事件']) {
      await addNode(label)
      await inView(`加入「${label}」後`)
    }
  }, 90000)

  it('接線（排版重新分層）後整張圖仍在畫布內', async () => {
    editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    await addNode('條件')
    await rename('分流點')
    await addNode('訊息')
    await rename('甲信')
    await addNode('訊息')
    await rename('乙信')
    await addNode('結束')
    await rename('收尾')
    await selectNode('分流點')
    await chooseLabel('是', ['甲信'])
    await inView('接「是」後')
    await chooseLabel('否', ['乙信'])
    await inView('接「否」後')
    await selectNode('甲信')
    await chooseLabel('送出後', ['收尾'])
    await selectNode('乙信')
    await chooseLabel('送出後', ['收尾'])
    await inView('接完線後')
  }, 90000)

  it('場景一規模（11 個節點）進頁就整張在畫布內', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await inView('進頁')
  }, 60000)
})

describe('F-21 小地圖與縮放列不蓋住節點', () => {
  it('符合視窗大小後，沒有節點卡與小地圖或縮放列重疊', async () => {
    editorBackend({ def: fan(), revision: 1 })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '符合視窗大小' }))
    await inView('按符合視窗大小後')
    const mm = document.querySelector<SVGElement>('.react-flow__minimap')
    expect(mm, '要有小地圖').toBeTruthy()
    // 小地圖在右下，預設 200x150、邊距 15（讀元素上的寬高，沒有就用預設）
    const mw = Number.parseFloat(mm!.style.width || mm!.getAttribute('width') || '') || 200
    const mh = Number.parseFloat(mm!.style.height || mm!.getAttribute('height') || '') || 150
    const mini = { x: BOX.w - 15 - mw, y: BOX.h - 15 - mh, w: mw, h: mh }
    // 縮放列在左下（四個按鈕＋百分比，約 200x44，邊距 12）
    const zoombar = { x: 12, y: BOX.h - 12 - 44, w: 200, h: 44 }
    for (const r of nodeRects()) {
      expect(intersects(r, mini), `節點「${r.name}」被小地圖蓋住`).toBe(false)
      expect(intersects(r, zoombar), `節點「${r.name}」被縮放列蓋住`).toBe(false)
    }
  }, 60000)
})
