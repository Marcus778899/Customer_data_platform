// @vitest-environment jsdom
/**
 * canvas-zoom-filter-fix：F-31 編輯畫布縮放（docs/ui/journey-builder.md「畫布縮放」）。
 * 沿用 canvas-fit-fix/fixtures 的版面模擬（容器 BOX，預設 1000x600；窄螢幕測試暫時改成 375 寬）。
 * 對 worker 的假設：
 *   - 自動縮放（載入、新增節點後）下限 75%（node-card-unify：原 70%；窄螢幕不再固定 85%，同樣 >= 75%）；圖比畫布寬時從「開始」節點起靠左，不再縮小。
 *   - 縮放列按鈕 aria-label：縮小、放大、看全圖（⤢，原「符合視窗大小」）、符合寬度（⇔，上限 100%）。
 *   - 「看全圖」縮到 60% 以下時出現文字「縮小檢視中，文字偏小」與按鈕「回到可讀大小」。
 *   - 面板標頭有「收合」鈕（名稱含「收合」），收合後出現名稱含「展開」的按鈕。
 *   - 小地圖 `.react-flow__minimap`：整張圖都在可見範圍內時不存在；窄螢幕（容器 <=640）不顯示縮放列與小地圖。
 *   - 節點卡高度 120px（node-card-unify：原 96px）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  cleanup, editorBackend, libraryItem, openEditor, resetEnv, screen, selectNode, skeleton, teardown, userEvent, waitFor, type Def,
} from '../journey-canvas-ui/fixtures'
import { BOX, mockLayout, nodeRects, outsideBox, restoreLayout, viewport } from '../canvas-fit-fix/fixtures'

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
/** 11 個節點、8 層，比 1000px 畫布在 75% 時還寬 */
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

const ready = async () => {
  await waitFor(() => expect(nodeRects().length).toBeGreaterThan(0))
  await new Promise((r) => setTimeout(r, 300))
}
const btn = (name: RegExp) => screen.getByRole('button', { name })
const settle = () => new Promise((r) => setTimeout(r, 600))

describe('F-31 自動縮放不低於下限', () => {
  it('11 個節點的圖進頁：縮放 >= 75%，從「開始」節點起靠左（不為了塞進畫布而縮小）', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    expect(viewport().zoom).toBeGreaterThanOrEqual(0.75 - 0.005)
    const rs = nodeRects()
    const left = Math.min(...rs.map((r) => r.x))
    expect(left, '最左的節點（開始）要在畫布內靠左').toBeGreaterThanOrEqual(-0.5)
    expect(left).toBeLessThanOrEqual(60)
    expect(Math.max(...rs.map((r) => r.x + r.w)), '前置條件：圖在 75% 時比畫布寬').toBeGreaterThan(BOX.w)
  }, 60000)

  it('新增節點後自動置中也不低於 75%', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    await userEvent.click(libraryItem(screen.getByRole('complementary', { name: '節點庫' }), '訊息'))
    await settle()
    expect(viewport().zoom).toBeGreaterThanOrEqual(0.75 - 0.005)
  }, 60000)

  it('窄螢幕（容器 375 寬）自動縮放不低於 75%，不顯示縮放列與小地圖', async () => {
    BOX.w = 375
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    expect(viewport().zoom).toBeGreaterThanOrEqual(0.75 - 0.005)
    expect(document.querySelector('.react-flow__minimap')).toBeNull()
    expect(screen.queryByRole('group', { name: '縮放' })).toBeNull()
  }, 60000)

  it('節點卡高度 120px', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    const hs = [...document.querySelectorAll<HTMLElement>('.react-flow__node')].map((n) => n.style.height)
    expect(new Set(hs)).toEqual(new Set(['120px']))
  }, 60000)
})

describe('F-31 開關面板、選取節點不改縮放', () => {
  it('選取節點開面板、收合、展開、換選節點：縮放全程不變', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    const z0 = viewport().zoom
    await selectNode('進入')
    await settle()
    expect(viewport().zoom).toBeCloseTo(z0, 3)
    await userEvent.click(btn(/收合/))
    await settle()
    expect(viewport().zoom).toBeCloseTo(z0, 3)
    await userEvent.click(btn(/展開/))
    await settle()
    expect(viewport().zoom).toBeCloseTo(z0, 3)
    await selectNode('甲1')
    await settle()
    expect(viewport().zoom).toBeCloseTo(z0, 3)
  }, 60000)
})

describe('F-31 縮放列：看全圖、符合寬度、上下限', () => {
  it('「看全圖」整張進畫布並水平垂直置中；低於 60% 出現提示，「回到可讀大小」回到 >= 75%', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    await userEvent.click(btn(/看全圖/))
    await waitFor(() => expect(outsideBox(nodeRects()), '看全圖後所有節點在畫布內').toEqual([]))
    const rs = nodeRects()
    const l = Math.min(...rs.map((r) => r.x))
    const r = BOX.w - Math.max(...rs.map((x) => x.x + x.w))
    const t = Math.min(...rs.map((x) => x.y))
    const b = BOX.h - Math.max(...rs.map((x) => x.y + x.h))
    expect(Math.abs(l - r), `左右留白 ${l}／${r}`).toBeLessThanOrEqual(2)
    expect(Math.abs(t - b), `上下留白 ${t}／${b}`).toBeLessThanOrEqual(2)
    expect(viewport().zoom, '前置條件：看全圖縮到 60% 以下').toBeLessThan(0.6)
    expect(await screen.findByText('縮小檢視中，文字偏小')).toBeTruthy()
    expect(document.querySelector('.react-flow__minimap'), '圖完全可見時隱藏小地圖').toBeNull()
    await userEvent.click(btn(/回到可讀大小/))
    await waitFor(() => expect(viewport().zoom).toBeGreaterThanOrEqual(0.75 - 0.005))
    expect(screen.queryByText('縮小檢視中，文字偏小')).toBeNull()
  }, 60000)

  it('圖超出畫面時有小地圖', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    expect(document.querySelector('.react-flow__minimap')).toBeTruthy()
  }, 60000)

  it('單一節點的圖沒有小地圖', async () => {
    editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    await ready()
    expect(document.querySelector('.react-flow__minimap')).toBeNull()
  }, 60000)

  it('「符合寬度」上限 100%：小圖按下後是 100%', async () => {
    editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    await ready()
    await userEvent.click(btn(/符合寬度/))
    await waitFor(() => expect(viewport().zoom).toBeCloseTo(1, 2))
  }, 60000)

  it('縮放下限 25%、上限 150%', async () => {
    editorBackend({ def: scene(), revision: 1 })
    await openEditor()
    await ready()
    for (let i = 0; i < 14; i++) await userEvent.click(btn(/^縮小$/))
    await settle()
    expect(viewport().zoom).toBeGreaterThanOrEqual(0.25 - 0.005)
    for (let i = 0; i < 25; i++) await userEvent.click(btn(/^放大$/))
    await settle()
    expect(viewport().zoom).toBeLessThanOrEqual(1.5 + 0.005)
  }, 90000)
})
