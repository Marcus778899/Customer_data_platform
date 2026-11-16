// @vitest-environment jsdom
/** journey-review-ui：核准／退回／撤回／停止對話框（rv-dialogs）、失敗提示，以及編輯畫布的送審對話框與草稿鎖定。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  API, AMY, REF_AMY, REF_WANG, RID, WANG, bodyText, brief, callsTo, cleanup, dialog, expectEnabled, expectAbsent, isDisabled,
  json, mustAction, openJourney, problem, resetEnv, review, screen, teardown, userEvent, waitFor, within,
} from './fixtures'
import { DRAFT_PATH, draftBody, editorBackend, openEditor } from '../journey-canvas-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const confirmBtn = (d: HTMLElement, name: string) => within(d).getByRole('button', { name })
const field = (d: HTMLElement, label: RegExp) => within(d).getByLabelText(label) as HTMLTextAreaElement | HTMLInputElement
const adminPending = () => openJourney({ me: WANG, kind: 'pending', submitter: REF_AMY })

describe('核准並發佈', () => {
  it('對話框「核准並發佈 v4？」：說明新進入從 v4、等待中客戶照 v3 走完；備註選填（上限 200 字）；焦點在備註；取消不送請求', async () => {
    const { api } = await adminPending()
    const trigger = await mustAction('核准並發佈')
    await userEvent.click(trigger)
    const d = await dialog('核准並發佈 v4？')
    expect(d.textContent).toContain('照 v3 走完')
    expect(d.textContent).toContain('發佈後 v4 不能修改')
    const note = field(d, /備註（選填）/)
    expect(note.getAttribute('maxlength')).toBe('200')
    expect(d.contains(document.activeElement)).toBe(true)
    await userEvent.click(confirmBtn(d, '取消'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(callsTo(api, 'POST', `${API}/reviews/${RID}/approve`)).toEqual([])
  })

  it('確認 → POST approve（reason 為備註）；成功後對話框關閉、旅程頁更新為 v4 並沒有核准、退回鈕', async () => {
    const { api } = await adminPending()
    await userEvent.click(await mustAction('核准並發佈'))
    const d = await dialog('核准並發佈 v4？')
    await userEvent.type(field(d, /備註（選填）/), '等待時間照建議')
    await userEvent.click(confirmBtn(d, '核准並發佈'))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/reviews/${RID}/approve`).length).toBe(1))
    expect((callsTo(api, 'POST', `${API}/reviews/${RID}/approve`)[0].body as { reason: string }).reason).toBe('等待時間照建議')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(bodyText()).toContain('最新版本 v4'))
    await expectAbsent('核准並發佈')
    await expectAbsent('退回')
  })

  it('不填備註也能核准（reason 省略或空）', async () => {
    const { api } = await adminPending()
    await userEvent.click(await mustAction('核准並發佈'))
    const d = await dialog('核准並發佈 v4？')
    await userEvent.click(confirmBtn(d, '核准並發佈'))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/reviews/${RID}/approve`).length).toBe(1))
    const reason = (callsTo(api, 'POST', `${API}/reviews/${RID}/approve`)[0].body as { reason?: string } | undefined)?.reason
    expect(reason === undefined || reason === '').toBe(true)
  })

  it('400 VALIDATION_FAILED：提示「維持「審核中」」，審核維持待處理（仍可退回），不顯示核准成功', async () => {
    await openJourney({
      me: WANG, kind: 'pending', submitter: REF_AMY,
      routes: {
        [`POST ${API}/reviews/${RID}/approve`]: () =>
          json({ type: 'about:blank', title: 'x', status: 400, code: 'VALIDATION_FAILED', detail: 'd', request_id: 'REQ-V', errors: [{ field: 'definition.nodes[0]', message: '缺 Source', rule: 1, node_id: null }] }, 400),
      },
    })
    await userEvent.click(await mustAction('核准並發佈'))
    const d = await dialog('核准並發佈 v4？')
    await userEvent.click(confirmBtn(d, '核准並發佈'))
    await waitFor(() => expect(bodyText()).toMatch(/維持「審核中」/))
    expect(bodyText()).not.toContain('最新版本 v4')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await expectEnabled('退回')
  })

  it('409 REVIEW_NOT_PENDING：提示「這筆審核已被處理」並有「重新載入」，按下重新取旅程', async () => {
    const { api } = await openJourney({
      me: WANG, kind: 'pending', submitter: REF_AMY,
      routes: { [`POST ${API}/reviews/${RID}/approve`]: () => problem(409, 'REVIEW_NOT_PENDING') },
    })
    await userEvent.click(await mustAction('核准並發佈'))
    const d = await dialog('核准並發佈 v4？')
    await userEvent.click(confirmBtn(d, '核准並發佈'))
    await waitFor(() => expect(bodyText()).toMatch(/已被(其他管理員)?處理/))
    const before = callsTo(api, 'GET', API).length
    await userEvent.click(await screen.findByRole('button', { name: '重新載入' }))
    await waitFor(() => expect(callsTo(api, 'GET', API).length).toBeGreaterThan(before))
  })
})

describe('退回', () => {
  it('對話框「退回這次送審」：顯示送審者；原因必填（1 到 200 字）；沒填按退回 → 「請填寫退回原因。」(role=alert)、不送請求', async () => {
    const { api } = await adminPending()
    await userEvent.click(await mustAction('退回'))
    const d = await dialog('退回這次送審')
    expect(d.textContent).toContain('陳小美')
    const reason = field(d, /退回原因（必填/)
    expect(reason.getAttribute('maxlength')).toBe('200')
    expect(d.contains(document.activeElement)).toBe(true)
    await userEvent.click(confirmBtn(d, '退回'))
    const alert = await within(d).findByRole('alert')
    expect(alert.textContent).toContain('請填寫退回原因。')
    expect(callsTo(api, 'POST', `${API}/reviews/${RID}/reject`)).toEqual([])
    expect(screen.getByRole('dialog')).toBeTruthy()
  })

  it('填原因 → POST reject { reason }；成功後關閉、旅程頁顯示已退回與原因，且有「修改後再送審」', async () => {
    const { api } = await adminPending()
    await userEvent.click(await mustAction('退回'))
    const d = await dialog('退回這次送審')
    await userEvent.type(field(d, /退回原因（必填/), '等待時間請改為 2 天')
    await userEvent.click(confirmBtn(d, '退回'))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/reviews/${RID}/reject`).length).toBe(1))
    expect(callsTo(api, 'POST', `${API}/reviews/${RID}/reject`)[0].body).toEqual({ reason: '等待時間請改為 2 天' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(bodyText()).toContain('等待時間請改為 2 天'))
    await expectEnabled('修改後再送審')
    await expectAbsent('核准並發佈')
  })

  it('Esc 取消：對話框關閉、不送請求、焦點回到觸發的「退回」鈕', async () => {
    const { api } = await adminPending()
    const trigger = await mustAction('退回')
    await userEvent.click(trigger)
    await dialog('退回這次送審')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(callsTo(api, 'POST', `${API}/reviews/${RID}/reject`)).toEqual([])
    expect(document.activeElement).toBe(trigger)
  })

  it('409 REVIEW_NOT_PENDING：提示已被處理與「重新載入」', async () => {
    await openJourney({
      me: WANG, kind: 'pending', submitter: REF_AMY,
      routes: { [`POST ${API}/reviews/${RID}/reject`]: () => problem(409, 'REVIEW_NOT_PENDING') },
    })
    await userEvent.click(await mustAction('退回'))
    const d = await dialog('退回這次送審')
    await userEvent.type(field(d, /退回原因（必填/), '不行')
    await userEvent.click(confirmBtn(d, '退回'))
    await waitFor(() => expect(bodyText()).toMatch(/已被(其他管理員)?處理/))
    expect(await screen.findByRole('button', { name: '重新載入' })).toBeTruthy()
  })
})

describe('撤回送審', () => {
  it('編輯者撤回自己送的：對話框「撤回送審？」、原因選填；確認 → POST withdraw；草稿解鎖、撤回鈕消失', async () => {
    const { api } = await openJourney({ me: AMY, kind: 'pending', submitter: REF_AMY })
    await userEvent.click(await mustAction('撤回送審'))
    const d = await dialog('撤回送審？')
    expect(d.textContent).toContain('草稿解鎖')
    const reason = field(d, /原因（選填）/)
    expect(reason.getAttribute('maxlength')).toBe('200')
    await userEvent.type(reason, '還要補一個條件')
    await userEvent.click(confirmBtn(d, '撤回送審'))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/reviews/${RID}/withdraw`).length).toBe(1))
    expect((callsTo(api, 'POST', `${API}/reviews/${RID}/withdraw`)[0].body as { reason: string }).reason).toBe('還要補一個條件')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(bodyText()).not.toContain('草稿已鎖定，不能修改'))
    await expectAbsent('撤回送審')
    await expectEnabled('編輯')
  })

  it('管理員可撤回別人送的（收在「更多」裡也行）', async () => {
    const { api } = await adminPending()
    await userEvent.click(await mustAction('撤回送審'))
    const d = await dialog('撤回送審？')
    await userEvent.click(confirmBtn(d, '撤回送審'))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/reviews/${RID}/withdraw`).length).toBe(1))
  })

  it('409 REVIEW_NOT_PENDING：提示已被處理與「重新載入」', async () => {
    await openJourney({
      me: AMY, kind: 'pending', submitter: REF_AMY,
      routes: { [`POST ${API}/reviews/${RID}/withdraw`]: () => problem(409, 'REVIEW_NOT_PENDING') },
    })
    await userEvent.click(await mustAction('撤回送審'))
    const d = await dialog('撤回送審？')
    await userEvent.click(confirmBtn(d, '撤回送審'))
    await waitFor(() => expect(bodyText()).toMatch(/已被(其他管理員)?處理|不是審核中/))
    expect(await screen.findByRole('button', { name: '重新載入' })).toBeTruthy()
  })
})

describe('停止旅程', () => {
  const open = (me = AMY, kind: 'active' | 'pending' = 'active') => openJourney({ me, kind })

  it('對話框「停止「棄單挽回」？」：danger 警告（role=alert：不能恢復、未送出的訊息取消）；原因必填；要輸入旅程名稱；焦點在原因', async () => {
    await open()
    await userEvent.click(await mustAction('停止旅程'))
    const d = await dialog('停止「棄單挽回」？')
    const alert = within(d).getByRole('alert')
    expect(alert.textContent).toContain('停止後不能恢復')
    expect(d.textContent).toContain('不會再發出')
    expect(field(d, /停止原因（必填/).getAttribute('maxlength')).toBe('200')
    expect(field(d, /輸入旅程名稱「棄單挽回」以確認/)).toBeTruthy()
    expect(d.contains(document.activeElement)).toBe(true)
  })

  it('確認鈕：原因與旅程名稱都對才可用；只填原因、名稱打錯、只有名稱都不行；停用時點下去不送請求', async () => {
    const { api } = await open()
    await userEvent.click(await mustAction('停止旅程'))
    const d = await dialog('停止「棄單挽回」？')
    const ok = confirmBtn(d, '停止旅程')
    expect(isDisabled(ok)).toBe(true)
    await userEvent.type(field(d, /停止原因（必填/), '活動結束')
    expect(isDisabled(ok)).toBe(true)
    await userEvent.type(field(d, /輸入旅程名稱/), '棄單挽')
    expect(isDisabled(ok)).toBe(true)
    await userEvent.click(ok)
    expect(callsTo(api, 'POST', `${API}/stop`)).toEqual([])
    await userEvent.type(field(d, /輸入旅程名稱/), '回')
    expect(isDisabled(ok)).toBe(false)
    await userEvent.clear(field(d, /停止原因（必填/))
    expect(isDisabled(ok)).toBe(true)
  })

  it('確認 → POST stop { reason }；成功後旅程頁顯示已停止、停止者與原因，編輯與停止不可用', async () => {
    const { api } = await open(WANG)
    await userEvent.click(await mustAction('停止旅程'))
    const d = await dialog('停止「棄單挽回」？')
    await userEvent.type(field(d, /停止原因（必填/), '活動結束')
    await userEvent.type(field(d, /輸入旅程名稱/), '棄單挽回')
    await userEvent.click(confirmBtn(d, '停止旅程'))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/stop`).length).toBe(1))
    expect(callsTo(api, 'POST', `${API}/stop`)[0].body).toEqual({ reason: '活動結束' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(bodyText()).toContain('這個旅程已停止，不能再修改或發佈'))
    expect(bodyText()).toContain('活動結束')
    for (const n of ['編輯', '停止旅程']) {
      const el = [...document.querySelectorAll<HTMLElement>('button, a')].find((e) => (e.textContent ?? '').trim() === n)
      if (el) expect(isDisabled(el)).toBe(true)
    }
  })

  it('送審中也能停止（編輯者）；Esc 取消不送請求', async () => {
    const { api } = await open(AMY, 'pending')
    const trigger = await mustAction('停止旅程')
    await userEvent.click(trigger)
    await dialog('停止「棄單挽回」？')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(callsTo(api, 'POST', `${API}/stop`)).toEqual([])
  })

  it('5xx：對話框保留（已輸入的內容不丟）並提示失敗，可再試', async () => {
    let fail = true
    const { api } = await openJourney({
      me: AMY, kind: 'active',
      routes: {
        [`POST ${API}/stop`]: () => (fail ? json({ type: 'about:blank', title: 'x', status: 500, code: 'INTERNAL', detail: 'd', request_id: 'REQ-STOP-500' }, 500) : json({ status: 'ABORTED' })),
      },
    })
    await userEvent.click(await mustAction('停止旅程'))
    const d = await dialog('停止「棄單挽回」？')
    await userEvent.type(field(d, /停止原因（必填/), '活動結束')
    await userEvent.type(field(d, /輸入旅程名稱/), '棄單挽回')
    await userEvent.click(confirmBtn(d, '停止旅程'))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/stop`).length).toBe(1))
    await waitFor(() => expect(bodyText()).toContain('REQ-STOP-500'))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(field(d, /停止原因（必填/).value).toBe('活動結束')
    fail = false
    await userEvent.click(confirmBtn(d, '停止旅程'))
    await waitFor(() => expect(callsTo(api, 'POST', `${API}/stop`).length).toBe(2))
  })
})

describe('編輯畫布：送審對話框與草稿鎖定', () => {
  const REVIEW = review()
  const pendingDraft = () => draftBody({ revision: 4, latest_review: REVIEW })

  it('按「送審」開對話框「把「棄單挽回」送審？」：說明草稿鎖定、核准前上線的仍是 v3；說明選填（上限 200 字）；取消不送請求', async () => {
    const ed = editorBackend()
    await openEditor()
    const trigger = screen.getByRole('button', { name: '送審' })
    await userEvent.click(trigger)
    const d = await dialog('把「棄單挽回」送審？')
    expect(d.textContent).toContain('送審後草稿鎖定')
    expect(d.textContent).toContain('上線的仍是 v3')
    const note = field(d, /給審核者的說明（選填）/)
    expect(note.getAttribute('maxlength')).toBe('200')
    expect(d.contains(document.activeElement)).toBe(true)
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`)).toEqual([])
    expect(document.activeElement).toBe(trigger)
  })

  it('確認送審 → POST submit { revision, note }；成功後草稿鎖定：顯示「已送審，等待管理員審核」「草稿已鎖定，不能修改」，改為「撤回送審」', async () => {
    const ed = editorBackend({
      routes: { [`POST ${DRAFT_PATH}/submit`]: () => json(REVIEW, 201) },
    })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    const d = await dialog('把「棄單挽回」送審？')
    await userEvent.type(field(d, /給審核者的說明（選填）/), '新增 VIP 分流')
    await userEvent.click(confirmBtn(d, '送審'))
    await waitFor(() => expect(callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`).length).toBe(1))
    const body = callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`)[0].body as { revision: number; note: string }
    expect(body.revision).toBe(ed.state.revision)
    expect(body.note).toBe('新增 VIP 分流')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(bodyText()).toContain('已送審，等待管理員審核'))
    expect(bodyText()).toContain('草稿已鎖定，不能修改')
    expect(screen.getByRole('button', { name: '撤回送審' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '送審' })).toBeNull()
  })

  it('不填說明也能送審（note 省略或空）', async () => {
    const ed = editorBackend({ routes: { [`POST ${DRAFT_PATH}/submit`]: () => json(REVIEW, 201) } })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    const d = await dialog('把「棄單挽回」送審？')
    await userEvent.click(confirmBtn(d, '送審'))
    await waitFor(() => expect(callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`).length).toBe(1))
    const note = (callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`)[0].body as { note?: string }).note
    expect(note === undefined || note === '').toBe(true)
  })

  it('送審失敗（5xx）：「送審失敗，請稍後再試」', async () => {
    editorBackend({
      routes: { [`POST ${DRAFT_PATH}/submit`]: () => json({ type: 'about:blank', title: 'x', status: 500, code: 'INTERNAL', detail: 'd', request_id: 'REQ-SUBMIT' }, 500) },
    })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    const d = await dialog('把「棄單挽回」送審？')
    await userEvent.click(confirmBtn(d, '送審'))
    await waitFor(() => expect(bodyText()).toContain('送審失敗，請稍後再試'))
  })

  it('載入時草稿已在送審中：鎖定（沒有「送審」、有「撤回送審」與鎖定說明）；節點面板欄位不可改', async () => {
    editorBackend({ routes: { [`GET ${DRAFT_PATH}`]: () => json(pendingDraft()) }, detail: { review_status: 'PENDING', latest_review: brief(REVIEW) } })
    await openEditor()
    expect(bodyText()).toContain('已送審，等待管理員審核')
    expect(bodyText()).toContain('草稿已鎖定，不能修改')
    expect(screen.queryByRole('button', { name: '送審' })).toBeNull()
    expect(screen.getByRole('button', { name: '撤回送審' })).toBeTruthy()
    const nodeBtn = [...document.querySelectorAll<HTMLButtonElement>('button[aria-pressed]')].find((b) => (b.textContent ?? '').includes('VIP 專屬關心'))
    if (nodeBtn) {
      await userEvent.click(nodeBtn)
      const box = screen.queryByLabelText('範本代碼') as HTMLInputElement | null
      if (box) expect(box.disabled || box.readOnly).toBe(true)
    }
  })

  it('鎖定中按「撤回送審」→ 對話框 → POST withdraw → 解鎖，「送審」回來', async () => {
    let locked = true
    const ed = editorBackend({
      routes: {
        [`GET ${DRAFT_PATH}`]: () => json(locked ? pendingDraft() : draftBody({ revision: 4 })),
        [`POST ${API}/reviews/${REVIEW.review_id}/withdraw`]: () => {
          locked = false
          return json({ ...REVIEW, status: 'WITHDRAWN', decided_by: REF_AMY, decided_at: '2026-11-07T03:00:00Z' })
        },
      },
      detail: { review_status: 'PENDING', latest_review: brief(REVIEW) },
    })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '撤回送審' }))
    const d = await dialog('撤回送審？')
    await userEvent.click(confirmBtn(d, '撤回送審'))
    await waitFor(() => expect(callsTo(ed.api, 'POST', `${API}/reviews/${REVIEW.review_id}/withdraw`).length).toBe(1))
    await waitFor(() => expect(screen.getByRole('button', { name: '送審' })).toBeTruthy())
    expect(bodyText()).not.toContain('草稿已鎖定，不能修改')
  })

  it('被退回的草稿（已解鎖）：顯示退回者與原因；可再送審（按鈕「送審」或「修改後再送審」）', async () => {
    const rej = review({ status: 'REJECTED', decided_by: REF_WANG, decided_at: '2026-11-06T08:30:00Z', reason: '等待時間請改為 2 天' })
    editorBackend({
      routes: { [`GET ${DRAFT_PATH}`]: () => json(draftBody({ latest_review: rej })) },
      detail: { review_status: 'REJECTED', latest_review: brief(rej) },
    })
    await openEditor()
    expect(bodyText()).toContain('等待時間請改為 2 天')
    expect(bodyText()).toContain('王小華')
    expect(screen.getByRole('button', { name: /^(修改後再)?送審$/ })).toBeTruthy()
    expect(bodyText()).not.toContain('草稿已鎖定，不能修改')
  })
})
