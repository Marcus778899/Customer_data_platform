// @vitest-environment jsdom
/**
 * journey-canvas-ui 追加測試（qa 問題 1、3a）：
 *  - 條件節點欄位下拉顯示白話欄位名，原始代碼收進該條件的「進階資訊」（docs/ui/journey-builder.md ed-condition；
 *    白話對照採設計稿 ed-condition：tier=會員等級、city=居住城市、trigger.properties.cart_total=購物車金額；
 *    沒有對照的欄位（cart_id）顯示原代碼）。option 的 value 仍是原始代碼。
 *  - 圖上有多個迴圈時逐一提醒，各自有「定位」。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  cleanup, editorBackend, openEditor, nodeButtons, selectNode, bodyText, screen, waitFor, waitForPut, userEvent, within,
  loopDef, resetEnv, teardown, type Def,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const optionsOf = (sel: HTMLElement) => [...(sel as HTMLSelectElement).options]
const optText = (sel: HTMLElement, value: string) => optionsOf(sel).find((o) => o.value === value)?.textContent?.trim()

describe('條件節點欄位白話名（ed-condition）', () => {
  it('欄位下拉：tier／city／cart_total 顯示白話名且 value 仍是原始代碼；沒有對照的 cart_id 顯示原代碼', async () => {
    editorBackend()
    await openEditor()
    await selectNode('是 VIP？')
    const sel = await screen.findByLabelText('欄位')
    await waitFor(() => expect(optionsOf(sel).some((o) => o.value === 'city')).toBe(true))
    expect(optText(sel, 'tier')).toBe('會員等級')
    expect(optText(sel, 'city')).toBe('居住城市')
    expect(optText(sel, 'trigger.properties.cart_total')).toContain('購物車金額')
    expect(optText(sel, 'trigger.properties.cart_total')).not.toContain('cart_total')
    expect(optText(sel, 'trigger.properties.cart_id')).toContain('cart_id')
  })

  it('已選欄位 tier 的那一行顯示「會員等級」，原始代碼不在可見欄位文字，只在預設收合的「進階資訊」裡', async () => {
    editorBackend()
    await openEditor()
    await selectNode('是 VIP？')
    const sel = (await screen.findByLabelText('欄位')) as HTMLSelectElement
    expect(sel.value).toBe('tier')
    expect(sel.selectedOptions[0].textContent?.trim()).toBe('會員等級')
    const panel = sel.closest('aside, [role="complementary"]') as HTMLElement
    const details = [...panel.querySelectorAll('details')].find((d) => d.querySelector('summary')?.textContent?.includes('進階資訊'))
    expect(details, '條件那一行找不到「進階資訊」').toBeTruthy()
    expect(details!.open).toBe(false)
    expect(details!.textContent).toContain('tier')
    const row = sel.closest('li, .cond-row') as HTMLElement
    expect(row.contains(details!), '「進階資訊」要在該條件那一行').toBe(true)
    // 條件行（排除 details 與 select 的 option）看不到原始代碼 tier
    const clone = row.cloneNode(true) as HTMLElement
    clone.querySelectorAll('details, select').forEach((n) => n.remove())
    expect(clone.textContent).not.toMatch(/\btier\b/)
  })

  it('換成「居住城市」後進階資訊顯示 city，且 PUT 的條件 field 是原始代碼 city', async () => {
    const ed = editorBackend()
    await openEditor()
    await selectNode('是 VIP？')
    const sel = await screen.findByLabelText('欄位')
    await waitFor(() => expect(optionsOf(sel).some((o) => o.value === 'city')).toBe(true))
    await userEvent.selectOptions(sel, 'city')
    const put = await waitForPut((b) => JSON.stringify(b.definition).includes('"field":"city"'), ed)
    expect(JSON.stringify(put.definition)).not.toContain('居住城市')
    expect((sel as HTMLSelectElement).selectedOptions[0].textContent?.trim()).toBe('居住城市')
    const details = (sel as HTMLElement).closest('li, .cond-row')!.querySelector('details')!
    expect(details.textContent).toContain('city')
  })
})

/** 兩個迴圈：A＝等結帳 24h ⇄ 再次提醒；B＝B 等待 ⇄ B 提醒 */
const twoLoops = (): Def => {
  const d = loopDef()
  d.nodes.find((n) => n.id === 'wait_ev')!.outputs = { occurred: 'wait_b', timeout: 'send_again' }
  d.nodes.push(
    { id: 'wait_b', type: 'WaitForEventNode', name: 'B 等待', config: { event_name: 'order_completed', timeout: 'P2D' }, outputs: { occurred: 'goal_1', timeout: 'send_b' } },
    { id: 'send_b', type: 'SendMessageNode', name: 'B 提醒', config: { channel_id: 'mock_default', template_id: 'cart_last' }, outputs: { sent: 'wait_b' } },
  )
  return d
}

describe('多個迴圈逐一提醒（D-050）', () => {
  it('兩個迴圈：每個迴圈各有提醒與自己的「定位」，定位選到該迴圈上的節點', async () => {
    editorBackend({ def: twoLoops() })
    await openEditor()
    await waitFor(() => expect(bodyText()).toContain('有迴圈'))
    await waitFor(() => expect(screen.getAllByRole('button', { name: /定位/ }).length).toBe(2))
    const [a, b] = screen.getAllByRole('button', { name: /定位/ })
    await userEvent.click(a)
    await waitFor(() => {
      const on = nodeButtons().filter((n) => n.getAttribute('aria-pressed') === 'true').map((n) => n.textContent ?? '')
      expect(on.length).toBe(1)
      expect(on[0]).toMatch(/等結帳 24h|再次提醒/)
    })
    await userEvent.click(b)
    await waitFor(() => {
      const on = nodeButtons().filter((n) => n.getAttribute('aria-pressed') === 'true').map((n) => n.textContent ?? '')
      expect(on.length).toBe(1)
      expect(on[0]).toMatch(/B 等待|B 提醒/)
    })
  })

  it('兩個迴圈的提醒都帶「圈數沒有上限」並分別點名迴圈上的節點', async () => {
    editorBackend({ def: twoLoops() })
    await openEditor()
    await waitFor(() => expect(bodyText()).toContain('有迴圈'))
    const hits = screen.getAllByText(/圈數沒有上限/)
    expect(hits.length).toBeGreaterThanOrEqual(2)
    expect(bodyText()).toMatch(/再次提醒/)
    expect(bodyText()).toMatch(/B 提醒|B 等待/)
    void within
  })
})
