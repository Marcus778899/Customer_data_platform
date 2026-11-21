// @vitest-environment jsdom
/** canvas-connect-drag：節點庫（取代頂部工具列）、按一下新增、自動接在選取節點後面。假設見 helpers.tsx。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  NINE, cleanup, editorBackend, nodeButtons, openEditor, resetEnv, selectNode, teardown, userEvent, waitFor, waitForPut, smallDef, libraryItem, screen,
} from '../journey-canvas-ui/fixtures'
import { branchDef, lib, outputsOf, refsTo, settle } from './helpers'
import { mockLayout, restoreLayout } from '../canvas-fit-fix/fixtures'

beforeEach(() => { resetEnv(); mockLayout() })
afterEach(() => { cleanup(); teardown(); restoreLayout() })

describe('節點庫的內容', () => {
  it('側欄 aside「節點庫」取代工具列「加入節點」與 details.addmenu；九個項目都是按鈕且各有一句說明', async () => {
    editorBackend()
    await openEditor()
    const l = lib()
    expect(l.tagName).toBe('ASIDE')
    expect(screen.queryByRole('toolbar', { name: '加入節點' })).toBeNull()
    expect(document.querySelector('details.addmenu')).toBeNull()
    for (const n of NINE) {
      const it = libraryItem(l, n)
      expect(it.tagName).toBe('BUTTON')
      expect((it.textContent ?? '').trim().length, `「${n}」項目只有名稱、沒有說明`).toBeGreaterThan(n.length + 3)
      expect(it.getAttribute('title') ?? '', `「${n}」項目的 title`).toContain(n)
    }
  })

  it('分四組：發送、等待、分支、結果；各組的節點照規格歸類', async () => {
    editorBackend()
    await openEditor()
    const text = (lib().textContent ?? '').replace(/\s+/g, '')
    const order = ['發送', '訊息', '等待', '等事件', '分支', '條件', 'A/B分流', '合流', '結果', '達標', '退出', '結束']
    let at = -1
    for (const k of order) {
      const i = text.indexOf(k, at + 1)
      expect(i, `節點庫文字裡「${k}」的位置應在前一項之後；文字：${text.slice(0, 80)}`).toBeGreaterThan(at)
      at = i
    }
  })
})

describe('按一下新增', () => {
  it('沒有選取節點：新節點放在空位、不接線（沒有任何出口指向它），並有 layout 座標、不與其他卡重疊', async () => {
    const ed = editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    await userEvent.click(libraryItem(lib(), '結束'))
    const put = await waitForPut((b) => b.definition.nodes.length === 7, ed)
    const added = put.definition.nodes.find((n) => !smallDef().nodes.some((o) => o.id === n.id))!
    expect(added.type).toBe('EndNode')
    expect(refsTo(put.definition, added.id)).toEqual([])
    const pos = put.layout!.nodes
    for (const n of put.definition.nodes) {
      if (n.id === added.id) continue
      const near = Math.abs(pos[n.id].x - pos[added.id].x) < 200 && Math.abs(pos[n.id].y - pos[added.id].y) < 120
      expect(near, `新節點與 ${n.id} 重疊`).toBe(false)
    }
  })

  it('Enter 也能新增（鍵盤）', async () => {
    const ed = editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    libraryItem(lib(), '等待').focus()
    await userEvent.keyboard('{Enter}')
    const put = await waitForPut((b) => b.definition.nodes.some((n) => n.type === 'WaitDelayNode' && !smallDef().nodes.some((o) => o.id === n.id)), ed)
    expect(put.definition.nodes.length).toBe(7)
  })

  it('已選取的節點沒有未連的出口（等待 1 小時：下一步已接）：新節點不接線，原連線不變', async () => {
    const ed = editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    await selectNode('等 1 小時')
    await userEvent.click(libraryItem(lib(), '訊息'))
    const put = await waitForPut((b) => b.definition.nodes.length === 7, ed)
    const added = put.definition.nodes.find((n) => !smallDef().nodes.some((o) => o.id === n.id))!
    expect(refsTo(put.definition, added.id)).toEqual([])
    expect(outputsOf(put.definition, 'wait_1')).toEqual({ next: 'cond_vip' })
  })

  it('已選取節點有未連出口（條件的「否」沒接）：新節點自動接在「否」後面，「是」不動', async () => {
    const ed = editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    await selectNode('是 VIP？')
    await userEvent.click(libraryItem(lib(), '結束'))
    const put = await waitForPut((b) => b.definition.nodes.length === 7, ed)
    const added = put.definition.nodes.find((n) => !smallDef().nodes.some((o) => o.id === n.id))!
    expect(outputsOf(put.definition, 'cond_vip')).toEqual({ yes: 'send_vip', no: added.id })
    expect(refsTo(put.definition, added.id)).toEqual(['cond_vip.no'])
  })

  it('兩個出口都沒接：依序接「是」再「否」；兩個都接好後再新增就不接線', async () => {
    const ed = editorBackend({ def: branchDef(), revision: 1 })
    await openEditor()
    const bump = async (n: number) => {
      await selectNode('分流點')
      await userEvent.click(libraryItem(lib(), '訊息'))
      return waitForPut((b) => b.definition.nodes.length === 4 + n, ed)
    }
    const p1 = await bump(1)
    const first = p1.definition.nodes.find((n) => n.id !== 'start' && !branchDef().nodes.some((o) => o.id === n.id))!
    expect(outputsOf(p1.definition, 'cond_1')).toEqual({ yes: first.id })
    const p2 = await bump(2)
    const second = p2.definition.nodes.find((n) => !branchDef().nodes.some((o) => o.id === n.id) && n.id !== first.id)!
    expect(outputsOf(p2.definition, 'cond_1')).toEqual({ yes: first.id, no: second.id })
    const p3 = await bump(3)
    const third = p3.definition.nodes.find((n) => !p2.definition.nodes.some((o) => o.id === n.id))!
    expect(refsTo(p3.definition, third.id)).toEqual([])
    expect(outputsOf(p3.definition, 'cond_1')).toEqual({ yes: first.id, no: second.id })
  }, 60000)

  it('新增後新節點被選取、右側面板打開（可接著命名）', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    await userEvent.click(libraryItem(lib(), '訊息'))
    await screen.findByLabelText('名稱')
    await waitFor(() => expect(nodeButtons().filter((b) => b.getAttribute('aria-pressed') === 'true').length).toBe(1))
    await settle()
  })
})
