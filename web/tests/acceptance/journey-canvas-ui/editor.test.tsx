// @vitest-environment jsdom
/** journey-canvas-ui：新建草稿、九種節點、連線、節點與旅程設定編輯、儲存（含 layout）、重新整理還原。假設見 fixtures.tsx。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DRAFT_PATH, EDIT, JID, NINE, addNode, byName, byType, chooseLabel, cleanup, editorBackend, json, nodeById, nodeButtons,
  openEditor, rename, resetEnv, screen, selectNode, skeleton, start, teardown, tryNodeButton, typeInto, userEvent,
  waitFor, waitForPut, within, backend, bodyText, loopDef, type Def,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const ID_RE = /^[a-z][a-z0-9_]*$/

describe('新建草稿', () => {
  it('/journeys/new：輸入旅程名稱、建立草稿 → POST /journeys/drafts { name } → 進入該旅程的編輯畫布（只有「開始」）', async () => {
    const api = backend({
      'POST /api/v1/journeys/drafts': () => json({ journey_id: JID, status: 'DRAFT', revision: 1 }, 201),
      [`GET ${DRAFT_PATH}`]: () =>
        json({ journey_id: JID, revision: 1, definition: skeleton('春季活動'), layout: null, updated_at: '2026-11-02T01:41:00Z', updated_by: null, latest_review: null }),
      [`GET /api/v1/journeys/${JID}`]: () => json({ journey_id: JID, name: '春季活動', status: 'DRAFT', latest_version: null, has_draft: true, review_status: null, updated_at: '2026-11-02T01:41:00Z', published_at: null, latest_review: null, description: null, versions: [], stopped_at: null, stop_reason: null, stopped_by: null }),
    })
    start('/journeys/new')
    await userEvent.type(await screen.findByLabelText('旅程名稱'), '春季活動')
    await userEvent.click(screen.getByRole('button', { name: '建立草稿' }))
    await screen.findByRole('toolbar', { name: '加入節點' })
    const post = api.calls.find((c) => c.method === 'POST' && c.path === '/api/v1/journeys/drafts')!
    expect(post.body).toEqual({ name: '春季活動' })
    expect(window.location.pathname).toBe(EDIT)
    expect(tryNodeButton('開始')).toBeTruthy()
  })

  it('新草稿（只有開始）：空狀態引導「因為什麼事件進入？」＋「設定進入事件」，狀態列「尚未儲存」，不自動送出 PUT', async () => {
    const ed = editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    expect(await screen.findByText('因為什麼事件進入？')).toBeTruthy()
    expect(bodyText()).toContain('還沒設定進入事件')
    expect(bodyText()).toContain('尚未儲存')
    expect(screen.getByRole('button', { name: '設定進入事件' })).toBeTruthy()
    await new Promise((r) => setTimeout(r, 1500))
    expect(ed.puts().length).toBe(0)
  })

  it('「設定進入事件」→ 填事件名稱 → 草稿出現 EventSourceNode 並儲存；之後條件欄位建議以該事件名稱呼叫 field-keys', async () => {
    const ed = editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    await userEvent.click(await screen.findByRole('button', { name: '設定進入事件' }))
    await userEvent.type(await screen.findByRole('textbox', { name: /事件/ }), 'cart_item_added')
    const put = await waitForPut((b) => byType(b.definition, 'EventSourceNode').length === 1, ed)
    expect(byType(put.definition, 'EventSourceNode')[0].config.event_name).toBe('cart_item_added')
    expect(put.revision).toBe(1)
    await addNode('條件')
    await waitFor(() => {
      const fk = ed.api.calls.filter((c) => c.path === '/api/v1/journeys/field-keys')
      expect(fk.length).toBeGreaterThan(0)
      expect(fk.some((c) => new URLSearchParams(c.search).get('event_name') === 'cart_item_added')).toBe(true)
    })
  })
})

describe('節點工具列', () => {
  it('toolbar「加入節點」有九種節點按鈕：訊息、等待、等事件、條件、A/B 分流、合流、達標、退出、結束', async () => {
    editorBackend()
    const tb = await openEditor()
    for (const n of NINE) expect(within(tb).getByRole('button', { name: n })).toBeTruthy()
  })
})

describe('九種節點、連線、編輯、儲存、還原', () => {
  it('加入九種節點並編輯、連線 → PUT 的 definition／layout 符合契約；重新整理後還原', async () => {
    const ed = editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    // 進入事件
    await userEvent.click(await screen.findByRole('button', { name: '設定進入事件' }))
    await userEvent.type(await screen.findByRole('textbox', { name: /事件/ }), 'cart_item_added')

    await addNode('訊息')
    await rename('提醒信')
    await typeInto('管道代碼', 'mock_default')
    await typeInto('範本代碼', 'cart_reminder')

    await addNode('等待')
    await rename('等兩小時')
    await typeInto('時間', '2')
    await chooseLabel('單位', ['小時'])

    await addNode('等事件')
    await rename('等結帳')
    await typeInto('等這個事件', 'order_completed')
    await typeInto('最多等', '1')
    await chooseLabel('單位', ['天'])

    await addNode('條件')
    await rename('是 VIP？')
    await userEvent.click(screen.getByRole('button', { name: '加入條件' }))
    await chooseLabel('欄位', ['tier'])
    await chooseLabel('比較', ['EQ', '等於'])
    await userEvent.type(screen.getByLabelText('值'), 'VIP')

    await addNode('A/B 分流')
    await rename('文案測試')
    await addNode('合流')
    await rename('會合點')
    await addNode('達標')
    await rename('結帳完成')
    await addNode('退出')
    await rename('已退訂')
    await typeInto(/退出原因/, '客戶已退訂行銷訊息')
    await addNode('結束')
    await rename('流程結束')

    // 連線
    await selectNode('提醒信')
    await chooseLabel('送出後', ['等兩小時'])
    await selectNode('等兩小時')
    await chooseLabel('等完之後', ['是 VIP？'])
    await selectNode('是 VIP？')
    await chooseLabel('是', ['會合點'])
    await chooseLabel('否', ['會合點'])
    await selectNode('會合點')
    await chooseLabel('會合後', ['流程結束'])
    await selectNode('等結帳')
    await chooseLabel('事件發生時', ['結帳完成'])
    await chooseLabel('逾時沒等到', ['提醒信'])

    const put = await waitForPut((b) => {
      const m = byName(b.definition, '提醒信')
      return !!m && m.outputs.sent !== undefined && byName(b.definition, '流程結束') !== undefined && byName(b.definition, '等結帳')?.outputs.timeout !== undefined
    }, ed)
    const d: Def = put.definition
    expect(d.nodes.map((n) => n.type).sort()).toEqual(
      ['ConditionNode', 'EndNode', 'EventSourceNode', 'ExitNode', 'GoalNode', 'MergeNode', 'SendMessageNode', 'SplitTestNode', 'StartNode', 'WaitDelayNode', 'WaitForEventNode'],
    )
    // id 規則：snake_case、旅程內唯一、<= 64
    const ids = d.nodes.map((n) => n.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const id of ids) { expect(id).toMatch(ID_RE); expect(id.length).toBeLessThanOrEqual(64) }
    expect(d.entry_node).toBe('start')

    const msg = byName(d, '提醒信')!
    expect(msg.config).toMatchObject({ channel_id: 'mock_default', template_id: 'cart_reminder' })
    expect(msg.outputs.sent).toBe(byName(d, '等兩小時')!.id)
    expect(byName(d, '等兩小時')!.config.duration).toBe('PT2H')
    expect(byName(d, '等兩小時')!.outputs.next).toBe(byName(d, '是 VIP？')!.id)
    const we = byName(d, '等結帳')!
    expect(we.config).toMatchObject({ event_name: 'order_completed', timeout: 'P1D' })
    expect(we.outputs).toMatchObject({ occurred: byName(d, '結帳完成')!.id, timeout: msg.id })
    const cond = byName(d, '是 VIP？')!
    expect(cond.config.condition).toMatchObject({ op: 'AND', children: [{ type: 'attribute', field: 'tier', operator: 'EQ', value: 'VIP' }] })
    expect(cond.outputs).toMatchObject({ yes: byName(d, '會合點')!.id, no: byName(d, '會合點')!.id })
    expect(byName(d, '會合點')!.outputs.next).toBe(byName(d, '流程結束')!.id)
    expect(byName(d, '已退訂')!.config.reason).toBe('客戶已退訂行銷訊息')
    const split = byName(d, '文案測試')!
    expect((split.config.variants as unknown[]).length).toBeGreaterThanOrEqual(2)

    // layout：每個節點都有座標；revision 是載入時的值
    expect(put.revision).toBeGreaterThanOrEqual(1)
    expect(put.layout).not.toBeNull()
    for (const id of ids) {
      const p = put.layout!.nodes[id]
      expect(p, `layout 缺 ${id}`).toBeTruthy()
      expect(Number.isFinite(p.x) && Number.isFinite(p.y)).toBe(true)
    }

    // 重新整理：重新載入同一網址，節點、名稱都還原
    cleanup()
    start(EDIT)
    await screen.findByRole('toolbar', { name: '加入節點' })
    await waitFor(() => expect(nodeButtons().length).toBeGreaterThanOrEqual(10))
    for (const n of ['提醒信', '等兩小時', '等結帳', '是 VIP？', '文案測試', '會合點', '結帳完成', '已退訂', '流程結束']) {
      await waitFor(() => expect(tryNodeButton(n), n).toBeTruthy())
    }
  }, 60000)

  it('連續兩次編輯：第二次 PUT 的 revision 是第一次回應的 revision（不是舊值）', async () => {
    const ed = editorBackend({ revision: 4 })
    await openEditor()
    await selectNode('VIP 專屬關心')
    await typeInto('範本代碼', 'cart_vip_2')
    await waitForPut((b) => b.revision === 4, ed)
    await typeInto('範本代碼', 'cart_vip_3')
    const second = await waitForPut((b) => b.revision === 5, ed)
    expect(nodeById(second.definition, 'send_vip')!.config.template_id).toBe('cart_vip_3')
  })

  it('刪除節點：面板「刪除節點」→ 節點與指向它的連線都從定義移除', async () => {
    const ed = editorBackend()
    await openEditor()
    await selectNode('VIP 專屬關心')
    await userEvent.click(screen.getByRole('button', { name: '刪除節點' }))
    const confirm = screen.queryByRole('dialog')
    if (confirm) await userEvent.click(within(confirm).getByRole('button', { name: /刪除/ }))
    const put = await waitForPut((b) => !nodeById(b.definition, 'send_vip'), ed)
    expect(nodeById(put.definition, 'cond_vip')!.outputs.yes ?? null).toBeNull()
  })

  it('選取節點：aria-pressed 切換、面板標題為節點名稱；「關閉面板」收起', async () => {
    editorBackend()
    await openEditor()
    await selectNode('等 1 小時')
    expect(screen.getByRole('heading', { name: '等 1 小時' })).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: '關閉面板' }))
    await waitFor(() => expect(nodeButtons().every((b) => b.getAttribute('aria-pressed') !== 'true')).toBe(true))
  })

  it('圖有「文字版節點清單」與縮放控制（放大、縮小、符合視窗大小）；節點 id 不顯示在畫面上', async () => {
    editorBackend()
    await openEditor()
    expect(bodyText()).toContain('文字版節點清單')
    for (const n of ['放大', '縮小', '符合視窗大小']) expect(screen.getAllByRole('button', { name: n }).length).toBeGreaterThan(0)
    expect(nodeButtons().map((b) => b.textContent).join(' ')).not.toContain('cond_vip')
  })

  it('既有 layout 被沿用：載入帶座標的草稿後不移動節點（PUT 的 layout 保留原座標）', async () => {
    const ed = editorBackend({ layout: { nodes: { start: { x: 0, y: 0 }, src_1: { x: 777, y: 123 }, wait_1: { x: 900, y: 0 }, cond_vip: { x: 1100, y: 0 }, send_vip: { x: 1300, y: 0 }, end_1: { x: 1500, y: 0 } } } })
    await openEditor()
    await selectNode('VIP 專屬關心')
    await typeInto('範本代碼', 'cart_vip_9')
    const put = await waitForPut((b) => nodeById(b.definition, 'send_vip')?.config.template_id === 'cart_vip_9', ed)
    expect(put.layout!.nodes.src_1).toEqual({ x: 777, y: 123 })
  })
})

describe('旅程設定', () => {
  it('頁首：旅程名稱、「草稿 v」徽章、麵包屑「編輯草稿」、版本流程（草稿／審核中／上線）、按鈕「旅程設定」「驗證」「送審」', async () => {
    editorBackend()
    await openEditor()
    expect(await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })).toBeTruthy()
    expect(within(screen.getByRole('navigation', { name: '麵包屑' })).getByText('編輯草稿')).toBeTruthy()
    const flow = screen.getByRole('list', { name: '版本流程' })
    for (const s of ['草稿', '審核中', '上線']) expect(within(flow).getByText(s)).toBeTruthy()
    for (const b of ['旅程設定', '驗證', '送審']) expect(screen.getByRole('button', { name: b })).toBeTruthy()
    expect(bodyText()).toContain('草稿基於 v3')
  })

  it('旅程設定面板：改名稱、描述、重新進入 → PUT 的 definition.name／description／settings.reentry', async () => {
    const ed = editorBackend()
    await openEditor()
    await userEvent.click(await screen.findByRole('button', { name: '旅程設定' }))
    await typeInto('旅程名稱', '棄單挽回 v2')
    await typeInto(/描述/, '提醒並導回結帳')
    await userEvent.click(screen.getByRole('radio', { name: /同一筆購物車只進入一次/ }))
    await typeInto('判斷用的欄位', 'cart_id')
    const apply = screen.queryByRole('button', { name: '套用' })
    if (apply) await userEvent.click(apply)
    const put = await waitForPut((b) => b.definition.name === '棄單挽回 v2', ed)
    expect(put.definition.description).toBe('提醒並導回結帳')
    expect(put.definition.settings.reentry).toMatchObject({ mode: 'DEDUP_KEY', dedup_key_path: '$.trigger.properties.cart_id' })
  })

  it('重新進入五選一：每位客戶只進入一次 → ONCE；不限制 → UNLIMITED', async () => {
    const ed = editorBackend()
    await openEditor()
    await userEvent.click(await screen.findByRole('button', { name: '旅程設定' }))
    for (const [label, mode] of [['每位客戶只進入一次', 'ONCE'], ['不限制', 'UNLIMITED']] as const) {
      await userEvent.click(screen.getByRole('radio', { name: new RegExp(label) }))
      const apply = screen.queryByRole('button', { name: '套用' })
      if (apply) await userEvent.click(apply)
      const put = await waitForPut((b) => (b.definition.settings.reentry as { mode?: string } | undefined)?.mode === mode, ed)
      expect(put).toBeTruthy()
    }
    expect(screen.getAllByRole('radio').length).toBeGreaterThanOrEqual(5)
  })
})

describe('迴圈提醒（D-050）', () => {
  it('有迴圈：狀態列顯示「有迴圈」「圈數沒有上限」與「定位」；定位後選取迴圈上的節點', async () => {
    editorBackend({ def: loopDef() })
    await openEditor()
    await waitFor(() => expect(bodyText()).toContain('有迴圈'))
    expect(bodyText()).toContain('圈數沒有上限')
    expect(bodyText()).toMatch(/再次提醒/)
    await userEvent.click(screen.getByRole('button', { name: /定位/ }))
    await waitFor(() => expect(nodeButtons().some((b) => b.getAttribute('aria-pressed') === 'true')).toBe(true))
  })

  it('沒有迴圈：不顯示迴圈提醒', async () => {
    editorBackend()
    await openEditor()
    await screen.findByRole('toolbar', { name: '加入節點' })
    expect(bodyText()).not.toContain('圈數沒有上限')
  })
})
