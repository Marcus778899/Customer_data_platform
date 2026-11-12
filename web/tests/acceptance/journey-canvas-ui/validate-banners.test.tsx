// @vitest-environment jsdom
/** journey-canvas-ui：驗證結果標在節點並可定位；DRAFT_CONFLICT、DRAFT_IN_REVIEW、JOURNEY_ABORTED、儲存失敗、VALIDATION_FAILED 提示條。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DRAFT_PATH, JID, bodyText, cleanup, draftBody, editorBackend, json, nodeButton, nodeButtons, openEditor,
  problem, ref, resetEnv, screen, selectNode, teardown, typeInto, userEvent, waitFor, waitForPut, within,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const report = () => ({
  valid: false,
  errors: [
    { field: 'definition.nodes[3].outputs.no', message: '「是 VIP？」的「否」出口還沒有連線。', rule: 4, node_id: 'cond_vip' },
    { field: 'definition.nodes[4].config.template_id', message: '「VIP 專屬關心」還沒填範本代碼。', rule: null, node_id: 'send_vip' },
    { field: 'definition.settings.goal', message: '達標目標還沒設定。', rule: null, node_id: null },
  ],
  warnings: [{ field: 'definition.nodes[2]', message: '「等 1 小時」目前只有 1 條路進來。', rule: null, node_id: 'wait_1' }],
})
const VALIDATE = `POST ${DRAFT_PATH}/validate`
const panel = async () => screen.findByRole('heading', { name: '驗證結果' })

describe('驗證結果', () => {
  it('按「驗證」→ POST validate；錯誤節點紅框＋「有錯誤」；面板「N 個錯誤要修正才能送審，另 M 個提醒」；狀態列「有 3 個錯誤」', async () => {
    const ed = editorBackend({ routes: { [VALIDATE]: () => json(report()) } })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    await panel()
    expect(ed.api.calls.some((c) => c.method === 'POST' && c.path === `${DRAFT_PATH}/validate`)).toBe(true)
    expect(bodyText()).toContain('3 個錯誤要修正才能送審')
    expect(bodyText()).toContain('另 1 個提醒')
    expect(bodyText()).toContain('有 3 個錯誤')
    expect(nodeButton('是 VIP？').textContent).toContain('有錯誤')
    expect(nodeButton('VIP 專屬關心').textContent).toContain('有錯誤')
    expect(nodeButton('等 1 小時').textContent).not.toContain('有錯誤') // 只有提醒
    expect(nodeButton('加入購物車').textContent).not.toContain('有錯誤')
  })

  it('每項錯誤顯示白話訊息；field 收進「進階資訊」；「定位節點」選取該節點；node_id 為 null 改「前往旅程設定」', async () => {
    editorBackend({ routes: { [VALIDATE]: () => json(report()) } })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    await panel()
    expect(bodyText()).toContain('「是 VIP？」的「否」出口還沒有連線。')
    expect(bodyText()).toContain('definition.nodes[3].outputs.no')
    const details = [...document.querySelectorAll('details')].find((d) => d.textContent?.includes('definition.nodes[3].outputs.no'))
    expect(details?.querySelector('summary')?.textContent).toContain('進階資訊')
    const locate = screen.getAllByRole('button', { name: '定位節點' })
    expect(locate.length).toBe(3) // 2 個錯誤＋1 個提醒（都有 node_id）
    await userEvent.click(locate[0])
    await waitFor(() => expect(nodeButton('是 VIP？').getAttribute('aria-pressed')).toBe('true'))
    await panel().catch(() => null)
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    await userEvent.click(await screen.findByRole('button', { name: '前往旅程設定' }))
    expect(await screen.findByLabelText('旅程名稱')).toBeTruthy()
  })

  it('「重新驗證」再打一次 validate；「關閉」收起；修好後（valid:true）不再顯示錯誤標記', async () => {
    let n = 0
    const ed = editorBackend({ routes: { [VALIDATE]: () => json(n++ === 0 ? report() : { valid: true, errors: [], warnings: [] }) } })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    await panel()
    await userEvent.click(screen.getByRole('button', { name: '重新驗證' }))
    await waitFor(() => expect(ed.api.calls.filter((c) => c.path === `${DRAFT_PATH}/validate`).length).toBe(2))
    await waitFor(() => expect(nodeButtons().some((b) => b.textContent?.includes('有錯誤'))).toBe(false))
    expect(screen.queryByText(/個錯誤要修正/)).toBeNull()
  })

  it('關閉鈕收起驗證結果', async () => {
    editorBackend({ routes: { [VALIDATE]: () => json(report()) } })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    const h = await panel()
    await userEvent.click(within(h.closest('aside, section, div[role], div') as HTMLElement).getByRole('button', { name: /關閉/ }))
    await waitFor(() => expect(screen.queryByRole('heading', { name: '驗證結果' })).toBeNull())
  })
})

describe('提示條（出現在畫布上方，編輯區保持原樣、不被清空）', () => {
  async function edit(routes: Parameters<typeof editorBackend>[0] = {}) {
    const ed = editorBackend(routes)
    await openEditor()
    await selectNode('VIP 專屬關心')
    await typeInto('範本代碼', 'cart_vip_x')
    return ed
  }

  it('409 DRAFT_CONFLICT：提示條＋載入最新草稿／複製我的修改；編輯區保留', async () => {
    await edit({ routes: { [`PUT ${DRAFT_PATH}`]: () => problem(409, 'DRAFT_CONFLICT') } })
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 })
    expect(alert.textContent).toMatch(/草稿已被/)
    expect(screen.getByRole('button', { name: '載入最新草稿' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '複製我的修改' })).toBeTruthy()
    expect(nodeButton('VIP 專屬關心')).toBeTruthy()
    expect((screen.getByLabelText('範本代碼') as HTMLInputElement).value).toBe('cart_vip_x')
  }, 30000)

  it('DRAFT_CONFLICT 提示條帶出修改者與時間（取衝突後重新 GET 的 updated_by／updated_at，本地時間）', async () => {
    let phase = 0
    editorBackend({
      revision: 4,
      routes: {
        [`GET ${DRAFT_PATH}`]: () =>
          json(draftBody({ revision: phase ? 6 : 4, updated_at: phase ? '2026-11-02T01:52:00Z' : '2026-11-02T01:41:00Z', updated_by: phase ? ref('u2', '王大明') : ref('u1', '陳小美') })),
        [`PUT ${DRAFT_PATH}`]: () => { phase = 1; return problem(409, 'DRAFT_CONFLICT') },
      },
    })
    await openEditor()
    await selectNode('VIP 專屬關心')
    await typeInto('範本代碼', 'cart_vip_x')
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 })
    await waitFor(() => expect(alert.textContent).toContain('王大明'))
    expect(alert.textContent).toContain('09:52')
    expect(alert.textContent).toContain('現在儲存會蓋掉他的修改')
  }, 30000)

  it('「載入最新草稿」：以最新內容與 revision 取代畫面；之後的 PUT 用新 revision', async () => {
    let phase = 0
    const latest = { ...draftBody().definition }
    latest.nodes = latest.nodes.map((n) => (n.id === 'send_vip' ? { ...n, name: '他改過的名稱' } : n))
    const ed = editorBackend({
      revision: 4,
      routes: {
        [`GET ${DRAFT_PATH}`]: () => json(phase ? draftBody({ revision: 6, definition: latest, updated_by: ref('u2', '王大明') }) : draftBody({ revision: 4 })),
        [`PUT ${DRAFT_PATH}`]: (c) => {
          if ((c.body as { revision: number }).revision !== 6) { phase = 1; return problem(409, 'DRAFT_CONFLICT') }
          return json(draftBody({ revision: 7 }))
        },
      },
    })
    await openEditor()
    await selectNode('VIP 專屬關心')
    await typeInto('範本代碼', 'cart_vip_x')
    await screen.findByRole('button', { name: '載入最新草稿' }, { timeout: 8000 })
    await userEvent.click(screen.getByRole('button', { name: '載入最新草稿' }))
    await waitFor(() => expect(nodeButton('他改過的名稱')).toBeTruthy())
    await selectNode('他改過的名稱')
    await typeInto('範本代碼', 'cart_after')
    await waitForPut((b) => b.revision === 6, ed)
  }, 30000)

  it('409 DRAFT_IN_REVIEW：「這份草稿已送審，不能修改」＋「撤回送審」；畫面內容保留', async () => {
    await edit({ routes: { [`PUT ${DRAFT_PATH}`]: () => problem(409, 'DRAFT_IN_REVIEW') } })
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 })
    expect(alert.textContent).toContain('這份草稿已送審，不能修改')
    expect(bodyText()).toContain('要修改請先撤回送審')
    expect(screen.getByRole('button', { name: '撤回送審' })).toBeTruthy()
    expect(nodeButton('VIP 專屬關心')).toBeTruthy()
  }, 30000)

  it('409 JOURNEY_ABORTED：「這個旅程已停止，不能再修改或發佈」＋停止者與時間＋「查看旅程」連結＋「複製我的修改」', async () => {
    await edit({
      detail: { status: 'ABORTED', stopped_at: '2026-11-02T01:30:00Z', stop_reason: '活動結束', stopped_by: ref('u3', '王小華') },
      routes: { [`PUT ${DRAFT_PATH}`]: () => problem(409, 'JOURNEY_ABORTED') },
    })
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 })
    expect(alert.textContent).toContain('這個旅程已停止，不能再修改或發佈')
    await waitFor(() => expect(alert.textContent).toContain('王小華'))
    expect(alert.textContent).toContain('09:30')
    expect(alert.textContent).toContain('你的未儲存修改無法送出')
    const link = screen.getByRole('link', { name: '查看旅程' })
    expect(link.getAttribute('href')).toContain(`/journeys/${JID}`)
    expect(screen.getByRole('button', { name: '複製我的修改' })).toBeTruthy()
  }, 30000)

  it('儲存失敗（5xx）：「儲存失敗，修改仍保留在畫面上」＋請求編號＋「重試」；重試成功後提示消失並回「已自動儲存」', async () => {
    let fail = true
    const ed = editorBackend({
      routes: {
        [`PUT ${DRAFT_PATH}`]: (c) => {
          if (fail) return json({ type: 'about:blank', title: 'x', status: 500, code: 'INTERNAL', detail: 'd', request_id: 'REQ-SAVE-500' }, 500)
          return json(draftBody({ revision: (c.body as { revision: number }).revision + 1 }))
        },
      },
    })
    await openEditor()
    await selectNode('VIP 專屬關心')
    await typeInto('範本代碼', 'cart_vip_x')
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 })
    expect(alert.textContent).toContain('儲存失敗，修改仍保留在畫面上')
    expect(alert.textContent).toContain('REQ-SAVE-500')
    expect((screen.getByLabelText('範本代碼') as HTMLInputElement).value).toBe('cart_vip_x')
    fail = false
    const before = ed.puts().length
    await userEvent.click(screen.getByRole('button', { name: '重試' }))
    await waitFor(() => expect(ed.puts().length).toBeGreaterThan(before))
    await waitFor(() => expect(screen.queryByText('儲存失敗，修改仍保留在畫面上')).toBeNull())
    await waitFor(() => expect(bodyText()).toContain('已自動儲存'))
  }, 30000)

  it('送審被擋（400 VALIDATION_FAILED）：「送審前驗證沒有通過」＋「有 N 個錯誤，已列在右側「驗證結果」」＋「看驗證結果」', async () => {
    editorBackend({
      routes: {
        [`POST ${DRAFT_PATH}/submit`]: () =>
          json({ type: 'about:blank', title: 'x', status: 400, code: 'VALIDATION_FAILED', detail: 'd', request_id: 'REQ-V', errors: report().errors }, 400),
        [VALIDATE]: () => json(report()),
      },
    })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    // 送審對話框屬 journey-review-ui；若有對話框，按下確認送出
    const dlg = screen.queryByRole('dialog')
    if (dlg) await userEvent.click(within(dlg).getByRole('button', { name: '送審' }))
    const alert = await screen.findByRole('alert', {}, { timeout: 8000 })
    expect(alert.textContent).toContain('送審前驗證沒有通過')
    expect(alert.textContent).toContain('有 3 個錯誤')
    await userEvent.click(screen.getByRole('button', { name: '看驗證結果' }))
    await panel()
    expect(nodeButton('是 VIP？').textContent).toContain('有錯誤')
  }, 30000)
})

