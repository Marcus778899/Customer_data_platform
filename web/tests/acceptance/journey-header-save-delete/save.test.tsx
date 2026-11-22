// @vitest-environment jsdom
/**
 * journey-header-save-delete：編輯頁手動儲存（canvas-interaction.md §5、設計稿 #sv、契約 §3「存檔時機由前端決定」）。
 * 狀態字四種、Ctrl／Cmd＋S、已儲存時停用、失敗保留內容並「重試儲存」、送審前自動儲存失敗不送、DRAFT_CONFLICT 與已刪除 404 有提示。
 */
import { fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DRAFT_PATH, JID, bodyText, brief, callsTo, cleanup, deferred, draftBody, editorBackend, isDisabled, json, makeDirty, mustSaveButton, nodeButton,
  openEditor, prob, puts, reasonIds, resetEnv, review, saveButton, screen, sleep, start, teardown, typeInto, userEvent, waitFor,
} from './helpers'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const SUBMIT = `POST ${DRAFT_PATH}/submit`
const SAVED = /已儲存（\d{2}:\d{2}）/

describe('四種狀態字與按鈕', () => {
  it('剛載入：「已儲存（09:41）」（取草稿的更新時間，本地時區）；「儲存草稿」停用、「送審」可用；不再有「尚未儲存」「已自動儲存」', async () => {
    editorBackend()
    await openEditor()
    expect(bodyText()).toContain('已儲存（09:41）')
    expect(bodyText()).not.toContain('已自動儲存')
    expect(bodyText()).not.toContain('尚未儲存')
    expect(isDisabled(mustSaveButton())).toBe(true)
    expect(isDisabled(screen.getByRole('button', { name: '送審' }))).toBe(false)
  })

  it('改了內容：「有未儲存的變更」、「儲存草稿」可按；不自動儲存（等 2 秒沒有任何 PUT）', async () => {
    const ed = editorBackend()
    await openEditor()
    await makeDirty()
    expect(isDisabled(mustSaveButton())).toBe(false)
    expect(bodyText()).not.toMatch(SAVED)
    await sleep(2000)
    expect(ed.puts().length).toBe(0)
    expect(bodyText()).toContain('有未儲存的變更')
  })

  it('按「儲存草稿」：PUT 一次（revision 4、帶改過的內容）；成功後回「已儲存（HH:mm）」、按鈕再度停用', async () => {
    const ed = editorBackend()
    await openEditor()
    await makeDirty('cart_vip_x')
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(ed.puts().length).toBe(1))
    const body = ed.lastPut()
    expect(body.revision).toBe(4)
    expect(body.definition.nodes.find((n) => n.id === 'send_vip')!.config.template_id).toBe('cart_vip_x')
    await waitFor(() => expect(bodyText()).toMatch(SAVED))
    expect(bodyText()).not.toContain('有未儲存的變更')
    await waitFor(() => expect(isDisabled(mustSaveButton())).toBe(true))
    // 已儲存時再按（或 Ctrl+S）不會多送
    await userEvent.click(mustSaveButton())
    await sleep(300)
    expect(ed.puts().length).toBe(1)
  })

  it('儲存中：狀態字「儲存中…」、「儲存草稿」與「送審」都停用、重複按不會多送；完成後恢復', async () => {
    const gate = deferred<Response>()
    const ed = editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => gate.promise } })
    await openEditor()
    await makeDirty()
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(bodyText()).toContain('儲存中…'))
    expect(isDisabled(mustSaveButton())).toBe(true)
    expect(isDisabled(screen.getByRole('button', { name: '送審' }))).toBe(true)
    await userEvent.click(mustSaveButton())
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    expect(ed.puts().length).toBe(1)
    expect(screen.queryByRole('dialog')).toBeNull()
    gate.resolve(json(draftBody({ revision: 5 })))
    await waitFor(() => expect(bodyText()).toMatch(SAVED))
    expect(isDisabled(screen.getByRole('button', { name: '送審' }))).toBe(false)
  })

  it('儲存失敗：狀態字「儲存失敗：…」、按鈕變「重試儲存」（沒有「儲存草稿」）、畫面內容保留；重試成功回已儲存', async () => {
    let fail = true
    const ed = editorBackend({
      routes: { [`PUT ${DRAFT_PATH}`]: () => (fail ? prob(503, 'UNAVAILABLE', 'REQ-S1') : json(draftBody({ revision: 5 }))) },
    })
    await openEditor()
    await makeDirty('cart_keep')
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(bodyText()).toMatch(/儲存失敗：?/))
    expect(screen.getByRole('button', { name: '重試儲存' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^儲存草稿/ })).toBeNull()
    expect((screen.getByLabelText('範本代碼') as HTMLInputElement).value).toBe('cart_keep')
    // 失敗不自動重送
    const n = ed.puts().length
    await sleep(1500)
    expect(ed.puts().length).toBe(n)
    fail = false
    await userEvent.click(screen.getByRole('button', { name: '重試儲存' }))
    await waitFor(() => expect(bodyText()).toMatch(SAVED))
    expect(saveButton()!.textContent).toMatch(/^儲存草稿/)
  })
})

describe('快速鍵 Ctrl／Cmd＋S', () => {
  it.each([['Ctrl', { ctrlKey: true }], ['Cmd', { metaKey: true }]])('%s＋S（焦點在輸入欄）：擋掉瀏覽器預設、PUT 一次', async (_n, mod) => {
    const ed = editorBackend()
    await openEditor()
    await makeDirty('cart_ks')
    const box = screen.getByLabelText('範本代碼')
    box.focus()
    const notPrevented = fireEvent.keyDown(box, { key: 's', code: 'KeyS', ...mod })
    expect(notPrevented, '要 preventDefault，避免瀏覽器的另存網頁').toBe(false)
    await waitFor(() => expect(ed.puts().length).toBe(1))
    expect(ed.lastPut().definition.nodes.find((n) => n.id === 'send_vip')!.config.template_id).toBe('cart_ks')
    await waitFor(() => expect(bodyText()).toMatch(SAVED))
  })

  it('沒有未儲存的變更時按 Ctrl＋S：不送 PUT', async () => {
    const ed = editorBackend()
    await openEditor()
    fireEvent.keyDown(document.body, { key: 's', code: 'KeyS', ctrlKey: true })
    await sleep(500)
    expect(ed.puts().length).toBe(0)
  })

  it('儲存失敗後按 Ctrl＋S 等同「重試儲存」', async () => {
    let fail = true
    const ed = editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => (fail ? prob(503, 'UNAVAILABLE', 'REQ-S2') : json(draftBody({ revision: 5 }))) } })
    await openEditor()
    await makeDirty()
    await userEvent.click(mustSaveButton())
    await screen.findByRole('button', { name: '重試儲存' })
    fail = false
    fireEvent.keyDown(document.body, { key: 's', code: 'KeyS', ctrlKey: true })
    await waitFor(() => expect(ed.puts().length).toBe(2))
    await waitFor(() => expect(bodyText()).toMatch(SAVED))
  })
})

describe('送審中鎖定', () => {
  it('草稿送審中：「儲存草稿」停用並附原因（說明已送審／鎖定）；Ctrl＋S 不送 PUT', async () => {
    const rv = review()
    const ed = editorBackend({
      routes: { [`GET ${DRAFT_PATH}`]: () => json(draftBody({ latest_review: rv })) },
      detail: { review_status: 'PENDING', latest_review: brief(rv) },
    })
    await openEditor()
    const btn = mustSaveButton()
    expect(isDisabled(btn)).toBe(true)
    const why = reasonIds(btn).map((i) => document.getElementById(i)?.textContent ?? '').join('')
    expect(why).toMatch(/送審|鎖定/)
    fireEvent.keyDown(document.body, { key: 's', code: 'KeyS', ctrlKey: true })
    await sleep(400)
    expect(ed.puts().length).toBe(0)
  })
})

describe('送審前自動儲存', () => {
  const REVIEW = review()
  const okSubmit = { [SUBMIT]: () => json(REVIEW, 201) }
  const confirmSubmit = async () => {
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    const d = await screen.findByRole('dialog', { name: /送審？/ })
    await userEvent.click([...d.querySelectorAll('button')].find((b) => b.textContent === '送審')!)
  }

  it('有未儲存的變更：先 PUT，成功後才 POST submit，且 submit 的 revision 用 PUT 回傳的值', async () => {
    const ed = editorBackend({ routes: okSubmit })
    await openEditor()
    await makeDirty()
    await confirmSubmit()
    await waitFor(() => expect(callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`).length).toBe(1))
    const order = ed.api.calls.filter((c) => (c.method === 'PUT' || c.method === 'POST') && c.path.startsWith(DRAFT_PATH)).map((c) => `${c.method} ${c.path.slice(DRAFT_PATH.length)}`)
    expect(order.slice(0, 2)).toEqual(['PUT ', 'POST /submit'])
    expect((ed.api.calls.find((c) => c.path === `${DRAFT_PATH}/submit`)!.body as { revision: number }).revision).toBe(5)
    await waitFor(() => expect(bodyText()).toContain('草稿已鎖定，不能修改'))
  })

  it('沒有未儲存的變更：直接送審，不多送 PUT', async () => {
    const ed = editorBackend({ routes: okSubmit })
    await openEditor()
    await confirmSubmit()
    await waitFor(() => expect(callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`).length).toBe(1))
    expect(ed.puts().length).toBe(0)
  })

  it('自動儲存失敗：不送審（沒有 POST submit）；顯示儲存失敗與「重試儲存」；內容保留', async () => {
    const ed = editorBackend({ routes: { ...okSubmit, [`PUT ${DRAFT_PATH}`]: () => prob(503, 'UNAVAILABLE', 'REQ-S3') } })
    await openEditor()
    await makeDirty('cart_nosend')
    await confirmSubmit()
    await waitFor(() => expect(bodyText()).toMatch(/儲存失敗/))
    await sleep(800)
    expect(callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`)).toEqual([])
    expect(screen.getByRole('button', { name: '重試儲存' })).toBeTruthy()
    expect((screen.getByLabelText('範本代碼') as HTMLInputElement).value).toBe('cart_nosend')
  })
})

describe('版本衝突與旅程已被刪除', () => {
  it('手動儲存回 409 DRAFT_CONFLICT：提示條（草稿已被…修改）、編輯內容保留（細節見 journey-canvas-ui/validate-banners）', async () => {
    editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => prob(409, 'DRAFT_CONFLICT', 'REQ-C') } })
    await openEditor()
    await makeDirty('cart_c')
    await userEvent.click(mustSaveButton())
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(/草稿已被/)
    expect((screen.getByLabelText('範本代碼') as HTMLInputElement).value).toBe('cart_c')
  })

  it('儲存時旅程已被刪除（404 JOURNEY_NOT_FOUND）：提示「這個旅程已被刪除」、編輯內容保留、不再顯示成已儲存', async () => {
    const ed = editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => prob(404, 'JOURNEY_NOT_FOUND', 'REQ-D') } })
    await openEditor()
    await makeDirty('cart_gone')
    await userEvent.click(mustSaveButton())
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(/已被刪除/)
    expect(bodyText()).not.toMatch(SAVED)
    expect((screen.getByLabelText('範本代碼') as HTMLInputElement).value).toBe('cart_gone')
    expect(nodeButton('VIP 專屬關心')).toBeTruthy()
    // 不再重複送（沒有自動重試）
    const n = ed.puts().length
    await sleep(1200)
    expect(ed.puts().length).toBe(n)
  })

  it('送審前自動儲存遇到 404：不送審，提示旅程已被刪除', async () => {
    const ed = editorBackend({
      routes: { [`PUT ${DRAFT_PATH}`]: () => prob(404, 'JOURNEY_NOT_FOUND', 'REQ-D2'), [SUBMIT]: () => json(review(), 201) },
    })
    await openEditor()
    await makeDirty()
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    const d = await screen.findByRole('dialog', { name: /送審？/ })
    await userEvent.click([...d.querySelectorAll('button')].find((b) => b.textContent === '送審')!)
    await waitFor(() => expect(bodyText()).toMatch(/已被刪除/))
    expect(callsTo(ed.api, 'POST', `${DRAFT_PATH}/submit`)).toEqual([])
  })
})

describe('其他', () => {
  it('載入失敗或無權限不顯示儲存鈕（不影響既有狀態頁）', async () => {
    editorBackend({ routes: { [`GET ${DRAFT_PATH}`]: () => prob(404, 'DRAFT_NOT_FOUND', 'REQ-N') } })
    start(`/journeys/${JID}/edit`)
    await screen.findByText('這個旅程目前沒有草稿。')
    expect(saveButton()).toBeNull()
  })

  it('PUT 帶的是最新內容：連續改兩處再存一次，PUT 同時含兩處修改', async () => {
    const ed = editorBackend()
    await openEditor()
    await makeDirty('cart_a')
    await typeInto('名稱', 'VIP 專屬關心二')
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(puts(ed.api).length).toBe(1))
    const n = ed.lastPut().definition.nodes.find((x) => x.id === 'send_vip')!
    expect(n.config.template_id).toBe('cart_a')
    expect(n.name).toBe('VIP 專屬關心二')
  })
})
