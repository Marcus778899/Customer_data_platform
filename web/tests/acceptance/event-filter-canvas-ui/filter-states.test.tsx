// @vitest-environment jsdom
/** event-filter-canvas-ui：欄位建議載入中／失敗改手動輸入／事件名稱未填；驗證錯誤依 field 標到對應條件；不支援的條件。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { saveDraftNow } from '../journey-canvas-ui/fixtures'
import {
  DRAFT_PATH, FK, and, bodyText, cleanup, deferred, efBackend, efDef, fieldKeysBody, filterOf, json, leaf, nodeButton, open,
  putWith, resetEnv, rowsOf, screen, SRC_LEGEND, teardown, userEvent, waitFor, within, optionCount,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

describe('欄位建議的載入與失敗', () => {
  it('載入中：role=status「正在載入欄位建議」，比較與值停用；回來後骨架消失', async () => {
    const gate = deferred<Response>()
    efBackend(efDef(), { [`GET ${FK}`]: () => gate.promise })
    const g = await open('加入購物車')
    await userEvent.click(within(g).getByRole('button', { name: '加入第一個條件' }))
    expect(await within(g).findByRole('status', { name: '正在載入欄位建議' })).toBeTruthy()
    expect((within(g).getByLabelText('比較') as HTMLSelectElement).disabled).toBe(true)
    expect((within(g).getByLabelText('值') as HTMLInputElement).disabled).toBe(true)
    gate.resolve(fieldKeysBody())
    await waitFor(() => expect(within(g).queryByRole('status', { name: '正在載入欄位建議' })).toBeNull())
    await waitFor(() => expect(optionCount(within(g).getByLabelText('欄位'))).toBeGreaterThan(2))
  })

  it('載入失敗：警告「欄位建議暫時載不到」＋「重試」，仍可手動輸入欄位代碼；重試成功後警告消失', async () => {
    let fail = true
    const ed = efBackend(efDef(), {
      [`GET ${FK}`]: () => (fail ? json({ type: 'about:blank', title: 'x', status: 503, code: 'UNAVAILABLE', detail: 'd', request_id: 'R1' }, 503) : fieldKeysBody()),
    })
    const g = await open('加入購物車')
    await userEvent.click(within(g).getByRole('button', { name: '加入第一個條件' }))
    await waitFor(() => expect(bodyText()).toContain('欄位建議暫時載不到'))
    expect(await within(g).findByLabelText('欄位代碼')).toBeTruthy()
    const before = ed.api.calls.filter((c) => c.path === FK).length
    fail = false
    await userEvent.click(within(g).getByRole('button', { name: '重試' }))
    await waitFor(() => expect(ed.api.calls.filter((c) => c.path === FK).length).toBeGreaterThan(before))
    await waitFor(() => expect(bodyText()).not.toContain('欄位建議暫時載不到'))
  })

  it('手動欄位代碼格式不符（Cart Total）：aria-invalid、role=alert「格式不符」並以 aria-describedby 綁定，不寫入草稿', async () => {
    const ed = efBackend(efDef(), { [`GET ${FK}`]: () => json({}, 500) })
    const g = await open('加入購物車')
    await userEvent.click(within(g).getByRole('button', { name: '加入第一個條件' }))
    const box = (await within(g).findByLabelText('欄位代碼')) as HTMLInputElement
    await userEvent.type(box, 'Cart Total')
    await waitFor(() => expect(box.getAttribute('aria-invalid')).toBe('true'))
    const alert = within(g).getByRole('alert')
    expect(alert.textContent).toContain('格式不符')
    expect(box.getAttribute('aria-describedby')).toContain(alert.id)
    await saveDraftNow() // 手動儲存：按儲存草稿後，不完整的值仍不可寫入
    await new Promise((r) => setTimeout(r, 500))
    for (const c of ed.puts()) expect(filterOf(c.body as never, 'src_1')?.children?.length ?? 0).toBe(0)
  })

  it('事件名稱還沒填：提示「先填上面的事件名稱」並可「手動輸入欄位代碼…」', async () => {
    efBackend(efDef(null, null, ''))
    const g = await open('加入購物車')
    await userEvent.click(within(g).getByRole('button', { name: '加入第一個條件' }))
    expect(bodyText()).toContain('先填上面的事件名稱')
    expect(within(g).getByRole('button', { name: '手動輸入欄位代碼…' })).toBeTruthy()
  })
})

const VALIDATE = `POST ${DRAFT_PATH}/validate`
const THREE = and(leaf('properties.cart_total', 'GT', '五百'), leaf('tier', 'EQ', 'VIP'), { type: 'segment', segment_id: 's1' })
const report = () => ({
  valid: false,
  errors: [
    { field: 'definition.nodes[1].config.event_filter.children[0].value', message: '「大於」要填數字。', rule: null, node_id: 'src_1' },
    { field: 'definition.nodes[1].config.event_filter.children[1].field', message: '只能比對事件屬性。', rule: null, node_id: 'src_1' },
    { field: 'definition.nodes[2].config.event_filter', message: '篩選疊太深（最多 5 層）。', rule: null, node_id: 'wait_ev' },
  ],
  warnings: [],
})

describe('驗證錯誤依 field 標到對應條件', () => {
  it('children[0].value 的訊息出現在第 1 列且值欄 aria-invalid；children[1].field 出現在第 2 列且欄位欄 aria-invalid；兩者不串列', async () => {
    efBackend(efDef(THREE), { [VALIDATE]: () => json(report()) })
    const g = await open('加入購物車')
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    await screen.findByRole('heading', { name: '驗證結果' })
    await waitFor(() => expect(nodeButton('加入購物車').textContent).toContain('有錯誤'))
    const [r1, r2] = rowsOf(g)
    await waitFor(() => expect(r1.textContent).toContain('「大於」要填數字。'))
    expect(within(r1).getByLabelText('值').getAttribute('aria-invalid')).toBe('true')
    expect(r1.textContent).not.toContain('只能比對事件屬性')
    expect(r2.textContent).toContain('只能比對事件屬性')
    expect(within(r2).getByLabelText('欄位').getAttribute('aria-invalid')).toBe('true')
    expect(r2.textContent).not.toContain('「大於」要填數字')
  })

  it('驗證結果按「定位節點」後，依 field 捲到對應條件列（scrollIntoView 或焦點落在該列內）', async () => {
    const scrolled: Element[] = []
    const orig = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function (this: Element) { scrolled.push(this) }
    try {
      efBackend(efDef(THREE), { [VALIDATE]: () => json(report()) })
      const g = await open('加入購物車')
      await userEvent.click(screen.getByRole('button', { name: '驗證' }))
      await screen.findByRole('heading', { name: '驗證結果' })
      const locates = await screen.findAllByRole('button', { name: '定位節點' })
      scrolled.length = 0
      await userEvent.click(locates[1]) // children[1].field
      const r2 = rowsOf(screen.getByRole('group', { name: SRC_LEGEND }))[1]
      await waitFor(() => {
        const hit = scrolled.some((el) => r2.contains(el)) || r2.contains(document.activeElement)
        expect(hit).toBe(true)
      })
      const [r1] = rowsOf(g)
      expect(scrolled.some((el) => r1?.contains(el) && !r2.contains(el))).toBe(false)
    } finally {
      Element.prototype.scrollIntoView = orig
    }
  })

  it('整個篩選層級的錯誤（超過 5 層）顯示在等事件的篩選區塊內，節點卡「有錯誤」', async () => {
    efBackend(efDef(null, and(leaf('properties.order_total', 'GTE', 500))), { [VALIDATE]: () => json(report()) })
    await open('等結帳 24h', /^只算符合條件的事件/)
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    await screen.findByRole('heading', { name: '驗證結果' })
    const g = screen.getByRole('group', { name: /^只算符合條件的事件/ })
    await waitFor(() => expect(g.textContent).toContain('篩選疊太深'))
    expect(nodeButton('等結帳 24h').textContent).toContain('有錯誤')
  })

  it('segment 葉節點：顯示「不支援的條件」「畫面無法編輯」，只能移除；移除後 PUT 不含該葉節點', async () => {
    const ed = efBackend(efDef(and(leaf('properties.cart_total', 'GT', 500), { type: 'segment', segment_id: 's1' })))
    const g = await open('加入購物車')
    expect(g.textContent).toContain('不支援的條件')
    expect(g.textContent).toContain('畫面無法編輯')
    const removes = within(g).getAllByRole('button', { name: '移除這個條件' })
    expect(removes.length).toBe(2)
    await userEvent.click(removes[1])
    const put = await putWith(ed, 'src_1', (f) => f !== null && !JSON.stringify(f).includes('segment'))
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.cart_total', 'GT', 500)))
  })
})
