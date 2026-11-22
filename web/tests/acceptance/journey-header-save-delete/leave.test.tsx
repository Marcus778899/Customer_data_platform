// @vitest-environment jsdom
/**
 * journey-header-save-delete：離開提醒（canvas-interaction.md §5、設計稿 #sv）。
 * 有未儲存變更時，返回、麵包屑、側欄連結跳三鈕對話框（預設焦點「繼續編輯」）；關分頁／重整用 beforeunload。
 */
import { fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DRAFT_PATH, EDIT, JOURNEY_URL, backControl, bodyText, cleanup, deferred, dlgButton, draftBody, editorBackend, json, leaveDialog, makeDirty,
  mustSaveButton, nodeButton, openEditor, prob, resetEnv, screen, selectNode, sleep, teardown, userEvent, waitFor, within,
} from './helpers'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const path = () => window.location.pathname

describe('沒有未儲存變更：直接離開', () => {
  it('點「返回旅程頁」直接回旅程頁，沒有對話框', async () => {
    editorBackend()
    await openEditor()
    expect(backControl(), '編輯頁標頭要有「返回旅程頁」').toBeTruthy()
    await userEvent.click(backControl()!)
    await waitFor(() => expect(path()).toBe(JOURNEY_URL))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('儲存之後再離開也不提醒', async () => {
    editorBackend()
    await openEditor()
    await makeDirty()
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(bodyText()).toMatch(/已儲存（\d{2}:\d{2}）/))
    await userEvent.click(backControl()!)
    await waitFor(() => expect(path()).toBe(JOURNEY_URL))
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('有未儲存變更：返回', () => {
  async function dirtyThenBack() {
    const ed = editorBackend()
    await openEditor()
    await makeDirty('cart_leave')
    await userEvent.click(backControl()!)
    return { ed, d: await leaveDialog() }
  }

  it('跳出「要把修改存成草稿嗎？」三鈕：繼續編輯、不儲存直接離開、儲存並離開；預設焦點在「繼續編輯」；還沒離開', async () => {
    const { d } = await dirtyThenBack()
    expect(d.getAttribute('aria-modal')).toBe('true')
    const keep = dlgButton(d, '繼續編輯')
    dlgButton(d, '不儲存直接離開')
    dlgButton(d, '儲存並離開')
    expect(document.activeElement).toBe(keep)
    expect(path()).toBe(EDIT)
  })

  it('「繼續編輯」與 Esc：關閉對話框、留在編輯頁、內容與未儲存狀態保留、不送 PUT', async () => {
    const { ed, d } = await dirtyThenBack()
    await userEvent.click(dlgButton(d, '繼續編輯'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(path()).toBe(EDIT)
    expect((screen.getByLabelText('範本代碼') as HTMLInputElement).value).toBe('cart_leave')
    expect(bodyText()).toContain('有未儲存的變更')
    await userEvent.click(backControl()!)
    await leaveDialog()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(path()).toBe(EDIT)
    expect(ed.puts().length).toBe(0)
  })

  it('「不儲存，直接離開」：不送 PUT，導向旅程頁', async () => {
    const { ed, d } = await dirtyThenBack()
    await userEvent.click(dlgButton(d, '不儲存直接離開'))
    await waitFor(() => expect(path()).toBe(JOURNEY_URL))
    expect(ed.puts().length).toBe(0)
  })

  it('「儲存並離開」：先 PUT（帶修改），成功後才導向旅程頁', async () => {
    const gate = deferred<Response>()
    const ed = editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => gate.promise } })
    await openEditor()
    await makeDirty('cart_leave')
    await userEvent.click(backControl()!)
    const d = await leaveDialog()
    await userEvent.click(dlgButton(d, '儲存並離開'))
    await waitFor(() => expect(ed.puts().length).toBe(1))
    await sleep(300)
    expect(path(), 'PUT 還沒成功前不能導頁').toBe(EDIT)
    gate.resolve(json(draftBody({ revision: 5 })))
    await waitFor(() => expect(path()).toBe(JOURNEY_URL))
    expect(ed.lastPut().definition.nodes.find((n) => n.id === 'send_vip')!.config.template_id).toBe('cart_leave')
  })

  it('儲存失敗：不離開；對話框改顯示錯誤，只剩「不儲存直接離開」「重試儲存」；重試成功後導頁', async () => {
    let fail = true
    const ed = editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => (fail ? prob(503, 'UNAVAILABLE', 'REQ-L1') : json(draftBody({ revision: 5 }))) } })
    await openEditor()
    await makeDirty()
    await userEvent.click(backControl()!)
    const d = await leaveDialog()
    await userEvent.click(dlgButton(d, '儲存並離開'))
    const failed = await screen.findByRole('dialog', { name: /儲存失敗/ })
    expect(failed.textContent).toMatch(/儲存失敗/)
    expect(path()).toBe(EDIT)
    dlgButton(failed, '不儲存直接離開')
    fail = false
    await userEvent.click(dlgButton(failed, '重試儲存'))
    await waitFor(() => expect(path()).toBe(JOURNEY_URL))
    expect(ed.puts().length).toBe(2)
  })

  it('儲存中不能離開：按返回不會導頁', async () => {
    const gate = deferred<Response>()
    editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => gate.promise } })
    await openEditor()
    await makeDirty()
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(bodyText()).toContain('儲存中…'))
    await userEvent.click(backControl()!)
    await sleep(300)
    expect(path()).toBe(EDIT)
    gate.resolve(json(draftBody({ revision: 5 })))
  })

  // canvas-uat-fix（canvas-interaction.md「儲存中離開」rt-leave）：儲存中「繼續編輯」可按、Esc 同效，只取消離開、不中斷儲存
  async function savingDialog() {
    const gate = deferred<Response>()
    const ed = editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: () => gate.promise } })
    await openEditor()
    await makeDirty('cart_leave')
    await userEvent.click(backControl()!)
    const d = await leaveDialog()
    await userEvent.click(dlgButton(d, '儲存並離開'))
    await waitFor(() => expect(ed.puts().length).toBe(1))
    return { ed, gate, d }
  }

  it('儲存中的離開對話框：內文「正在儲存草稿，存好就會離開。」；「繼續編輯」可按（未停用），另兩鈕停用', async () => {
    const { gate, d } = await savingDialog()
    expect(d.textContent).toContain('正在儲存草稿，存好就會離開。')
    const keep = dlgButton(d, '繼續編輯')
    expect(keep.disabled).toBe(false)
    expect(keep.getAttribute('aria-disabled')).not.toBe('true')
    expect(dlgButton(d, '不儲存直接離開').disabled || dlgButton(d, '不儲存直接離開').getAttribute('aria-disabled') === 'true').toBe(true)
    gate.resolve(json(draftBody({ revision: 5 })))
  })

  it.each([
    ['按「繼續編輯」', async (d: HTMLElement) => { await userEvent.click(dlgButton(d, '繼續編輯')) }],
    ['按 Esc', async () => { await userEvent.keyboard('{Escape}') }],
  ])('儲存中%s：關閉對話框、不中斷儲存（只送 1 次 PUT）、存好後也不導頁、留在編輯頁', async (_n, act) => {
    const { ed, gate, d } = await savingDialog()
    await act(d)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(path()).toBe(EDIT)
    gate.resolve(json(draftBody({ revision: 5 })))
    await waitFor(() => expect(bodyText()).toMatch(/已儲存（\d{2}:\d{2}）/))
    await sleep(300)
    expect(path(), '取消離開後，儲存完成不能再導頁').toBe(EDIT)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(ed.puts().length).toBe(1)
  })

  it('儲存中取消離開後，儲存失敗不跳「儲存失敗，還不能離開」對話框', async () => {
    const { gate } = await savingDialog()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    gate.resolve(prob(503, 'UNAVAILABLE', 'REQ-L2'))
    await sleep(300)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(path()).toBe(EDIT)
  })
})

describe('有未儲存變更：麵包屑與側欄連結', () => {
  it.each([
    ['麵包屑「旅程」', () => within(screen.getByRole('navigation', { name: '麵包屑' })).getByRole('link', { name: '旅程' })],
    ['側欄「旅程」', () => within(screen.getAllByRole('navigation', { name: /主要導覽/ })[0]).getByRole('link', { name: '旅程' })],
  ])('%s：跳離開提醒；「繼續編輯」留下；「不儲存直接離開」才導向 /journeys', async (_n, link) => {
    const ed = editorBackend()
    await openEditor()
    await makeDirty()
    await userEvent.click(link())
    const d = await leaveDialog()
    expect(document.activeElement).toBe(dlgButton(d, '繼續編輯'))
    expect(path()).toBe(EDIT)
    await userEvent.click(dlgButton(d, '繼續編輯'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(path()).toBe(EDIT)
    await userEvent.click(link())
    await userEvent.click(dlgButton(await leaveDialog(), '不儲存直接離開'))
    await waitFor(() => expect(path()).toBe('/journeys'))
    expect(ed.puts().length).toBe(0)
  })

  it('沒有未儲存變更時麵包屑與側欄連結直接導頁', async () => {
    editorBackend()
    await openEditor()
    await userEvent.click(within(screen.getByRole('navigation', { name: '麵包屑' })).getByRole('link', { name: '旅程' }))
    await waitFor(() => expect(path()).toBe('/journeys'))
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('關閉分頁／重新整理：beforeunload', () => {
  const fire = () => {
    const ev = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(ev)
    return ev.defaultPrevented
  }
  it('有未儲存變更：beforeunload 被 preventDefault（瀏覽器內建提醒）；儲存後不再攔；離開編輯頁後也不再攔', async () => {
    editorBackend()
    await openEditor()
    expect(fire(), '沒有變更不攔').toBe(false)
    await makeDirty()
    expect(fire()).toBe(true)
    await userEvent.click(mustSaveButton())
    await waitFor(() => expect(bodyText()).toMatch(/已儲存（\d{2}:\d{2}）/))
    expect(fire()).toBe(false)
    await makeDirty('cart_again')
    expect(fire()).toBe(true)
    await userEvent.click(backControl()!)
    await userEvent.click(dlgButton(await leaveDialog(), '不儲存直接離開'))
    await waitFor(() => expect(path()).toBe(JOURNEY_URL))
    expect(fire(), '離開編輯頁後要移除監聽').toBe(false)
  })
})

describe('鍵盤：對話框裡的 Delete／Esc 不影響畫布選取', () => {
  it('焦點在 role=alertdialog 內按 Delete／Esc：選取的節點不被刪除、也不取消選取', async () => {
    editorBackend()
    await openEditor()
    await selectNode('VIP 專屬關心')
    const host = document.createElement('div')
    host.setAttribute('role', 'alertdialog')
    host.innerHTML = '<button id="adlg-x" type="button">確定</button>'
    document.body.appendChild(host)
    const btn = host.querySelector('button')!
    btn.focus()
    fireEvent.keyDown(btn, { key: 'Delete', code: 'Delete' })
    fireEvent.keyDown(btn, { key: 'Escape', code: 'Escape' })
    await sleep(100)
    expect(nodeButton('VIP 專屬關心').getAttribute('aria-pressed')).toBe('true')
    host.remove()
  })
})
