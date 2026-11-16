// @vitest-environment jsdom
/** event-filter-canvas-ui：入口與等事件節點新增／編輯／刪除篩選，PUT 的 event_filter 符合契約 §4。假設見 fixtures.tsx。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  FK, SRC_LEGEND, WAIT_LEGEND, addCond, and, bodyText, choose, cleanup, efBackend, efDef, filterOf, leaf, open, optionCount,
  putWith, resetEnv, rowsOf, screen, selectNode, statusText, teardown, typeInto, userEvent, waitFor, within,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const GT500 = and(leaf('properties.cart_total', 'GT', 500))

describe('入口節點：新增', () => {
  it('沒有篩選：空狀態「目前沒有篩選」＋「加入第一個條件」；只是打開面板不送 PUT', async () => {
    const ed = efBackend()
    const g = await open('加入購物車')
    expect(within(g).getByText('目前沒有篩選')).toBeTruthy()
    expect(within(g).getByRole('button', { name: '加入第一個條件' })).toBeTruthy()
    await new Promise((r) => setTimeout(r, 1200))
    expect(ed.puts().length).toBe(0)
  })

  it('開面板時以該節點事件名稱載入欄位建議 field-keys?event_name=add_to_cart', async () => {
    const ed = efBackend()
    await open('加入購物車')
    await waitFor(() => expect(ed.api.calls.some((c) => c.path === FK && new URLSearchParams(c.search).get('event_name') === 'add_to_cart')).toBe(true))
  })

  it('購物車金額 大於 500 → PUT event_filter = AND[attribute properties.cart_total GT 500（number）]；摘要句與節點卡「只限…」', async () => {
    const ed = efBackend()
    const g = await open('加入購物車')
    const row = await addCond(g, ['properties.cart_total', '購物車金額'], ['GT', '大於'], '500')
    expect((within(row).getByLabelText('欄位') as HTMLSelectElement).selectedOptions[0].textContent).toContain('購物車金額')
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(GT500)
    expect(typeof (filterOf(put, 'src_1')!.children[0] as { value: unknown }).value).toBe('number')
    expect(statusText(g)).toContain('購物車金額')
    expect(statusText(g)).toContain('500')
    await waitFor(() => expect([...document.querySelectorAll('button[aria-pressed]')].some((b) => /只限.*500/.test(b.textContent ?? ''))).toBe(true))
  })

  it('文字欄：優惠碼 等於 → string；IN 以逗號分隔 → 去空白的字串陣列', async () => {
    const ed = efBackend()
    const g = await open('加入購物車')
    await addCond(g, ['properties.coupon_code', '優惠碼'], ['EQ', '等於'], 'SPRING')
    await addCond(g, ['properties.utm_source', '來源管道'], ['IN', '是其中之一'], 'line, email')
    const put = await putWith(ed, 'src_1', (f) => (f?.children.length ?? 0) === 2)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.coupon_code', 'EQ', 'SPRING'), leaf('properties.utm_source', 'IN', ['line', 'email'])))
  })

  it('沒有值的比較（有填）→ 值欄停用，送 IS_NOT_NULL 不帶 value', async () => {
    const ed = efBackend()
    const g = await open('加入購物車')
    const row = await addCond(g, ['properties.coupon_code'], ['IS_NOT_NULL', '有填'])
    expect((within(row).getByLabelText('值') as HTMLInputElement).disabled).toBe(true)
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.coupon_code', 'IS_NOT_NULL')))
  })

  it('任一符合 → op OR', async () => {
    const ed = efBackend()
    const g = await open('加入購物車')
    await addCond(g, ['properties.cart_total'], ['GT'], '500')
    await addCond(g, ['properties.item_count'], ['GTE'], '3')
    await userEvent.click(within(within(g).getByRole('group', { name: '組合方式' })).getByRole('button', { name: '任一符合' }))
    const put = await putWith(ed, 'src_1', (f) => f?.op === 'OR')
    expect(filterOf(put, 'src_1')).toEqual({ op: 'OR', children: [leaf('properties.cart_total', 'GT', 500), leaf('properties.item_count', 'GTE', 3)] })
  })

  it('手動輸入欄位代碼：選「手動輸入欄位代碼…」→ 填鍵 my_key → field 為 properties.my_key', async () => {
    const ed = efBackend()
    const g = await open('加入購物車')
    await userEvent.click(within(g).getByRole('button', { name: '加入第一個條件' }))
    await waitFor(() => expect(optionCount(within(g).getByLabelText('欄位'))).toBeGreaterThan(2))
    await choose(within(g).getByLabelText('欄位'), ['手動輸入欄位代碼'])
    await userEvent.type(await within(g).findByLabelText('欄位代碼'), 'my_key')
    await choose(within(g).getByLabelText('比較'), ['EQ', '等於'])
    await userEvent.type(within(g).getByLabelText('值'), 'x')
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.my_key', 'EQ', 'x')))
  })

  it('條件沒填完不寫入草稿：只選欄位 → 提示「尚未完成」，PUT 沒有該葉節點', async () => {
    const ed = efBackend()
    const g = await open('加入購物車')
    await userEvent.click(within(g).getByRole('button', { name: '加入第一個條件' }))
    await waitFor(() => expect(optionCount(within(g).getByLabelText('欄位'))).toBeGreaterThan(2))
    await choose(within(g).getByLabelText('欄位'), ['properties.cart_total'])
    expect(statusText(g)).toContain('尚未完成')
    await new Promise((r) => setTimeout(r, 1800))
    for (const c of ed.puts()) expect(filterOf(c.body as never, 'src_1')?.children?.length ?? 0).toBe(0)
  })

  it('一個完整、一個沒填完 → PUT 只含完整那條', async () => {
    const ed = efBackend()
    const g = await open('加入購物車')
    await addCond(g, ['properties.cart_total'], ['GT'], '500')
    await userEvent.click(within(g).getByRole('button', { name: '加入條件' }))
    await waitFor(() => expect(rowsOf(g).length).toBe(2))
    await putWith(ed, 'src_1', (f) => f !== null)
    await new Promise((r) => setTimeout(r, 1500))
    expect(filterOf(ed.lastPut(), 'src_1')).toEqual(GT500)
  })
})

describe('等事件節點', () => {
  it('「只算符合條件的事件（選填）」：訂單金額至少 500 → 寫在 wait_ev.config.event_filter，timeout 不變、入口不受影響', async () => {
    const ed = efBackend()
    const g = await open('等結帳 24h', WAIT_LEGEND)
    expect(within(g).getByText('目前沒有篩選')).toBeTruthy()
    await addCond(g, ['properties.order_total', '訂單金額'], ['GTE', '至少'], '500')
    const put = await putWith(ed, 'wait_ev', (f) => f !== null)
    expect(filterOf(put, 'wait_ev')).toEqual(and(leaf('properties.order_total', 'GTE', 500)))
    expect(put.definition.nodes.find((n) => n.id === 'wait_ev')!.config.timeout).toBe('PT24H')
    expect(filterOf(put, 'src_1')).toBeNull()
  })

  it('摘要句寫明其他事件不算等到、繼續等到逾時', async () => {
    efBackend(efDef(null, and(leaf('properties.order_total', 'GTE', 500))))
    const g = await open('等結帳 24h', WAIT_LEGEND)
    expect(statusText(g)).toContain('不算等到')
    expect(statusText(g)).toContain('逾時')
  })
})

describe('編輯與刪除', () => {
  it('載入既有條件：欄位、比較、值還原，欄位代碼收在預設收合的進階資訊；改值 → PUT 新值（仍是 number）', async () => {
    const ed = efBackend(efDef(GT500))
    const g = await open('加入購物車')
    const row = rowsOf(g)[0]
    expect((within(row).getByLabelText('欄位') as HTMLSelectElement).value).toBe('properties.cart_total')
    expect((within(row).getByLabelText('比較') as HTMLSelectElement).value).toBe('GT')
    expect((within(row).getByLabelText('值') as HTMLInputElement).value).toBe('500')
    const details = row.querySelector('details')!
    expect(details.open).toBe(false)
    expect(details.textContent).toContain('properties.cart_total')
    await typeInto('值', '800')
    const put = await putWith(ed, 'src_1', (f) => JSON.stringify(f).includes('800'))
    expect(filterOf(put, 'src_1')).toEqual(and(leaf('properties.cart_total', 'GT', 800)))
  })

  it('改欄位會重設比較與值；重設後沒填完不寫入（PUT 不會有 coupon_code）', async () => {
    const ed = efBackend(efDef(GT500))
    const g = await open('加入購物車')
    const row = rowsOf(g)[0]
    await waitFor(() => expect([...(within(row).getByLabelText('欄位') as HTMLSelectElement).options].some((o) => o.value === 'properties.coupon_code')).toBe(true))
    await choose(within(row).getByLabelText('欄位'), ['properties.coupon_code'])
    expect((within(row).getByLabelText('值') as HTMLInputElement).value).toBe('')
    await new Promise((r) => setTimeout(r, 1500))
    for (const c of ed.puts()) expect(JSON.stringify(filterOf(c.body as never, 'src_1'))).not.toContain('coupon_code')
  })

  it('移除最後一個條件 → PUT null（不彈確認）；「已清除 1 項條件」＋「復原」；復原 → PUT 回原條件', async () => {
    const ed = efBackend(efDef(GT500))
    const g = await open('加入購物車')
    await userEvent.click(within(g).getByRole('button', { name: '移除這個條件' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    await putWith(ed, 'src_1', (f) => f === null)
    expect(bodyText()).toContain('已清除 1 項條件')
    expect(within(g).getByText('目前沒有篩選')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: '復原' }))
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(GT500)
    await waitFor(() => expect(rowsOf(g).length).toBe(1))
  })

  it('「清除篩選」清掉 2 項 → null、「已清除 2 項條件」；復原帶回兩項', async () => {
    const two = and(leaf('properties.cart_total', 'GT', 500), leaf('properties.coupon_code', 'NEQ', 'TEST100'))
    const ed = efBackend(efDef(two))
    const g = await open('加入購物車')
    await userEvent.click(within(g).getByRole('button', { name: '清除篩選' }))
    await putWith(ed, 'src_1', (f) => f === null)
    expect(bodyText()).toContain('已清除 2 項條件')
    await userEvent.click(screen.getByRole('button', { name: '復原' }))
    const put = await putWith(ed, 'src_1', (f) => f !== null)
    expect(filterOf(put, 'src_1')).toEqual(two)
  })

  it('進入條件節點不可刪除（沒有「刪除節點」）；等事件節點可以', async () => {
    efBackend()
    await open('加入購物車')
    expect(screen.queryByRole('button', { name: '刪除節點' })).toBeNull()
    await selectNode('等結帳 24h')
    expect(await screen.findByRole('button', { name: '刪除節點' })).toBeTruthy()
    expect(SRC_LEGEND.test('只讓符合條件的事件進入（選填）')).toBe(true)
  })

  it('改事件名稱：已填條件保留，欄位建議以新名稱重載', async () => {
    const ed = efBackend(efDef(GT500))
    const g = await open('加入購物車')
    await typeInto(/事件名稱/, 'cart_item_added')
    await waitFor(() => expect(ed.api.calls.some((c) => c.path === FK && new URLSearchParams(c.search).get('event_name') === 'cart_item_added')).toBe(true))
    expect(rowsOf(g).length).toBe(1)
    const put = await putWith(ed, 'src_1', () => JSON.stringify(ed.lastPut().definition.nodes[1].config).includes('cart_item_added'))
    expect(filterOf(put, 'src_1')).toEqual(GT500)
  })
})
