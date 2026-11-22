// @vitest-environment jsdom
/** architect review 追加：驗證／載入最新草稿失敗不冒充儲存失敗；儲存進行中點導覽連結不被吞掉。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DRAFT_PATH, bodyText, cleanup, deferred, draftBody, editorBackend, json, leaveDialog, makeDirty, mustSaveButton, openEditor, prob, resetEnv,
  screen, teardown, userEvent, waitFor, within,
} from '../../acceptance/journey-header-save-delete/helpers'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const crumbLink = () => within(screen.getByRole('navigation', { name: '麵包屑' })).getByRole('link', { name: '旅程' })

describe('驗證失敗', () => {
  it('validate 回 500：顯示「驗證沒有完成」＋請求編號，不叫人重試儲存；標頭維持已儲存；可關閉', async () => {
    editorBackend({ routes: { [`POST ${DRAFT_PATH}/validate`]: () => prob(500, 'INTERNAL', 'REQ-V1') } })
    await openEditor()
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('驗證沒有完成')
    expect(alert.textContent).toContain('REQ-V1')
    expect(bodyText()).not.toContain('重試儲存')
    expect(bodyText()).not.toContain('儲存失敗')
    expect(bodyText()).toMatch(/已儲存（\d{2}:\d{2}）/)
    await userEvent.click(within(alert).getByRole('button', { name: '關閉' }))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })
})

describe('載入最新草稿失敗', () => {
  it('衝突後按「載入最新草稿」失敗：顯示載入失敗（不是儲存失敗），可再試一次或關閉', async () => {
    let armed = false
    editorBackend({
      routes: {
        [`GET ${DRAFT_PATH}`]: () => (armed ? prob(503, 'UNAVAILABLE', 'REQ-L1') : json(draftBody())),
        [`PUT ${DRAFT_PATH}`]: () => prob(409, 'DRAFT_CONFLICT', 'REQ-C'),
      },
    })
    await openEditor()
    await makeDirty()
    await userEvent.click(mustSaveButton())
    const load = await screen.findByRole('button', { name: '載入最新草稿' })
    armed = true
    await userEvent.click(load)
    await waitFor(() => expect(bodyText()).toContain('載入最新草稿失敗'))
    expect(bodyText()).toContain('REQ-L1')
    expect(bodyText()).not.toContain('重試儲存')
    const alert = screen.getByRole('alert')
    expect(within(alert).getByRole('button', { name: '再試一次' })).toBeTruthy()
    await userEvent.click(within(alert).getByRole('button', { name: '關閉' }))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })
})

describe('儲存進行中點導覽連結', () => {
  it('不被吞掉：開出儲存中的離開對話框，儲存成功後導頁', async () => {
    const gate = deferred<Response>()
    editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => gate.promise } })
    await openEditor()
    await makeDirty()
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(bodyText()).toContain('儲存中…'))
    await userEvent.click(crumbLink())
    await leaveDialog()
    expect(window.location.pathname).toMatch(/\/edit$/)
    gate.resolve(json(draftBody({ revision: 5 })))
    await waitFor(() => expect(window.location.pathname).toBe('/journeys'))
  })

  it('儲存失敗：不導頁，改顯示儲存失敗對話框', async () => {
    const gate = deferred<Response>()
    editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => gate.promise } })
    await openEditor()
    await makeDirty()
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(bodyText()).toContain('儲存中…'))
    await userEvent.click(crumbLink())
    await leaveDialog()
    gate.resolve(prob(503, 'UNAVAILABLE', 'REQ-S9'))
    await screen.findByRole('dialog', { name: /儲存失敗/ })
    expect(window.location.pathname).toMatch(/\/edit$/)
  })
})
