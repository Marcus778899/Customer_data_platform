// @vitest-environment jsdom
/**
 * canvas-connect-drag：出口圓點、鍵盤替代、面板「下一步」、移除連線、未接出口提示、首次提示。
 * 拖拉本身（拉線、拖入、放開在空白處的選單）jsdom 無法驗證，由 uat 目視；這裡驗證 DOM 結構與所有可用鍵盤／面板達成的同效操作。
 * 假設見 helpers.tsx。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  EDIT, chooseLabel, cleanup, editorBackend, nodeButtons, resetEnv, screen, selectNode, smallDef, start, teardown, userEvent, waitFor, waitForPut, within,
} from '../journey-canvas-ui/fixtures'
import { branchDef, handleOf, oneFreeDef, openBare, outHandles, outputsOf, settle } from './helpers'
import { mockLayout, restoreLayout } from '../canvas-fit-fix/fixtures'

beforeEach(() => { resetEnv(); mockLayout(); localStorage.clear() })
afterEach(() => { cleanup(); teardown(); restoreLayout(); localStorage.clear() })

const freeText = () => /還有 \d+ 個出口沒接下一步/.exec(document.body.textContent ?? '')?.[0] ?? null
const edgeBtn = () =>
  screen.queryAllByRole('button').filter((b) => /移除連線/.test(`${b.getAttribute('aria-label') ?? ''}${b.getAttribute('title') ?? ''}${b.textContent ?? ''}`))
async function selectEdge(id: string) {
  const wrap = await waitFor(() => {
    const w = document.querySelector(`[data-testid="rf__edge-${id}"]`)
    expect(w, `沒有邊 ${id}`).toBeTruthy()
    return w!
  })
  await userEvent.click(wrap.querySelector('path')!)
  await waitFor(() => expect(edgeBtn().length, '選取連線後要出現「移除連線」鈕').toBeGreaterThan(0))
}

describe('出口圓點', () => {
  it('條件節點有「是」「否」兩個獨立圓點（button，在節點容器內、不在卡片按鈕裡），aria-label 照規格', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(outHandles('是 VIP？').length).toBe(2))
    const yes = handleOf('是 VIP？', '是')
    const no = handleOf('是 VIP？', '否')
    expect(yes).not.toBe(no)
    for (const h of [yes, no]) {
      expect(h.tagName).toBe('BUTTON')
      expect(h.closest('.react-flow__node')).toBeTruthy()
      expect(h.parentElement!.closest('button'), '圓點不可巢狀在卡片 button 裡').toBeNull()
      expect(h.getAttribute('aria-label')).toMatch(/^從[「『]是 VIP？[」』]的[「『](是|否)[」』]出口拉線到下一步$/)
    }
  })

  it('單一出口（等待）一個圓點，aria-label 不帶出口名；終點節點（結束）沒有圓點', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(outHandles('等 1 小時').length).toBe(1))
    expect(outHandles('等 1 小時')[0].getAttribute('aria-label')).toMatch(/^從[「『]等 1 小時[」』]的出口拉線到下一步$/)
    expect(outHandles('流程結束').length).toBe(0)
  })

  it('已接好 data-connected="true"、未接 data-connected="false"；接上後立即改變', async () => {
    editorBackend({ def: oneFreeDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(handleOf('分流點', '是').getAttribute('data-connected')).toBe('true'))
    expect(handleOf('分流點', '否').getAttribute('data-connected')).toBe('false')
    await selectNode('分流點')
    await chooseLabel('否', ['另一個結束'])
    await waitFor(() => expect(handleOf('分流點', '否').getAttribute('data-connected')).toBe('true'))
  })

  it('按 Enter：焦點移到右側面板該出口的「下一步」下拉', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(outHandles('是 VIP？').length).toBe(2))
    handleOf('是 VIP？', '否').focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('否')))
    expect(document.activeElement!.tagName).toBe('SELECT')
    handleOf('是 VIP？', '是').focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('是')))
  })
})

describe('面板「下一步」（完整替代）', () => {
  it('第一個選項是「（還沒接）」（value 空）；選目標＝連線、選回「（還沒接）」＝移除，PUT 反映', async () => {
    const ed = editorBackend({ def: oneFreeDef(), revision: 1 })
    await openBare()
    await selectNode('分流點')
    const sel = screen.getByLabelText('否') as HTMLSelectElement
    expect(sel.options[0].value).toBe('')
    expect(sel.options[0].textContent).toBe('（還沒接）')
    await userEvent.selectOptions(sel, 'end_b')
    await waitForPut((b) => outputsOf(b.definition, 'cond_1').no === 'end_b', ed)
    await userEvent.selectOptions(sel, '')
    const put = await waitForPut((b) => outputsOf(b.definition, 'cond_1').no === undefined && outputsOf(b.definition, 'cond_1').yes === 'end_a', ed)
    expect(outputsOf(put.definition, 'cond_1')).toEqual({ yes: 'end_a' })
  })

  it('同出口原連線被取代：「是」改接另一個節點，只剩一條', async () => {
    const ed = editorBackend({ def: oneFreeDef(), revision: 1 })
    await openBare()
    await selectNode('分流點')
    await userEvent.selectOptions(screen.getByLabelText('是'), 'end_b')
    const put = await waitForPut((b) => outputsOf(b.definition, 'cond_1').yes === 'end_b', ed)
    expect(outputsOf(put.definition, 'cond_1')).toEqual({ yes: 'end_b' })
  })

  it('下拉不可選自己；「進入條件」不是可選目標', async () => {
    editorBackend({ def: oneFreeDef(), revision: 1 })
    await openBare()
    await selectNode('分流點')
    const values = [...(screen.getByLabelText('是') as HTMLSelectElement).options].map((o) => o.value)
    expect(values).not.toContain('cond_1')
    expect(values).not.toContain('start')
  })
})

describe('未接出口提示', () => {
  it('必填出口沒接：顯示「還有 N 個出口沒接下一步」；接好後消失；移除後回來', async () => {
    const ed = editorBackend({ def: oneFreeDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(freeText()).toBe('還有 1 個出口沒接下一步'))
    await selectNode('分流點')
    await userEvent.selectOptions(screen.getByLabelText('否'), 'end_b')
    await waitFor(() => expect(freeText()).toBeNull())
    await userEvent.selectOptions(screen.getByLabelText('否'), '')
    await waitFor(() => expect(freeText()).toBe('還有 1 個出口沒接下一步'))
    await waitForPut((b) => outputsOf(b.definition, 'cond_1').no === undefined, ed)
  })

  it('兩個出口都沒接：N = 2', async () => {
    editorBackend({ def: branchDef(), revision: 1 })
    await openBare()
    await waitFor(() => expect(freeText()).toBe('還有 2 個出口沒接下一步'))
  })
})

describe('移除連線', () => {
  it('點線選取 → 「移除連線」鈕 → 按下：該出口不再指向任何節點，其他連線不動；live region 報讀', async () => {
    const ed = editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await selectEdge('cond_vip:yes')
    await userEvent.click(edgeBtn()[0])
    const put = await waitForPut((b) => outputsOf(b.definition, 'cond_vip').yes === undefined, ed)
    expect(outputsOf(put.definition, 'wait_1')).toEqual({ next: 'cond_vip' })
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => /已移除連線/.test(s.textContent ?? ''))).toBe(true))
    await waitFor(() => expect(edgeBtn().length).toBe(0))
  })

  it('選取連線後按 Delete 同效（節點不會被刪）', async () => {
    const ed = editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await selectEdge('wait_1:next')
    await userEvent.keyboard('{Delete}')
    const put = await waitForPut((b) => outputsOf(b.definition, 'wait_1').next === undefined, ed)
    expect(put.definition.nodes.length).toBe(6)
  })

  it('選取節點按 Delete 刪掉節點與指向它的連線；焦點在輸入框時 Delete 不刪', async () => {
    const ed = editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await selectNode('VIP 專屬關心')
    await userEvent.click(screen.getByLabelText('名稱'))
    await userEvent.keyboard('{Delete}')
    await settle()
    const card = () => nodeButtons().find((b) => (b.textContent ?? '').includes('VIP 專屬關心'))
    expect(card()).toBeTruthy()
    card()!.focus()
    await userEvent.keyboard('{Delete}')
    const put = await waitForPut((b) => !b.definition.nodes.some((n) => n.id === 'send_vip'), ed)
    expect(outputsOf(put.definition, 'cond_vip').yes ?? null).toBeNull()
  })
})

describe('首次提示', () => {
  const hint = () =>
    [...document.querySelectorAll<HTMLElement>('p, div, span')].find((e) => /從節點右邊的圓點按住/.test(e.textContent ?? '') && (e.textContent ?? '').length < 200 && e.children.length < 4)

  it('第一次進編輯畫布顯示：在畫布容器內、不在會平移縮放的 viewport 裡（不跟著捲走）；有「知道了」', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    const h = await waitFor(() => {
      const x = hint()
      expect(x, '找不到首次提示文字「從節點右邊的圓點按住…」').toBeTruthy()
      return x!
    })
    expect(h.closest('.react-flow__viewport')).toBeNull()
    const wrap = h.closest('.canvas-wrap') as HTMLElement | null
    expect(wrap, '提示要在 .canvas-wrap 內').toBeTruthy()
    expect(within(wrap!).getByRole('button', { name: '知道了' })).toBeTruthy()
  })

  it('按「知道了」後消失，重開編輯器（同一個 localStorage）不再出現', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openBare()
    await userEvent.click(await screen.findByRole('button', { name: '知道了' }))
    await waitFor(() => expect(hint()).toBeUndefined())
    cleanup()
    start(EDIT)
    await screen.findByRole('complementary', { name: '節點庫' })
    await settle()
    expect(hint()).toBeUndefined()
    expect(screen.queryByRole('button', { name: '知道了' })).toBeNull()
  })
})
