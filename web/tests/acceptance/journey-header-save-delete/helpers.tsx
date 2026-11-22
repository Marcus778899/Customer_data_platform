/**
 * journey-header-save-delete 驗收測試共用輔助（qa 撰寫，worker 不可改）。
 * 規格：docs/ui/canvas-interaction.md §4～§6、設計稿 docs/ui/canvas-interaction/index.html（#jh、#sv、#dl）、
 * docs/design/journey-delete.md、docs/contracts/journeys.md §3、§3.6（API 一律 mock，錯誤碼與角色以這些文件為準）。
 *
 * 對 worker 的假設（規格沒寫死、測試定下來的）：
 *   - 旅程頁標頭：「更多動作」按鈕 aria-label 固定為「更多動作」、aria-haspopup="menu"、aria-expanded；選單 role="menu"，項目 role="menuitem"
 *     （可帶一行小字說明，名稱以動作名稱開頭即可）；分頁列 `nav` aria-label「旅程頁分頁」，三個連結「節點圖」「旅程進度」「審核紀錄」，目前頁 aria-current="page"。
 *   - 停用 = aria-disabled="true"（可聚焦）＋ aria-describedby 指向原因元素；原因元素在標題列（h1）之後、分頁列之前，不在按鈕、選單或動作區容器內。
 *   - 編輯頁標頭：返回控制項 aria-label「返回旅程頁」（連結或按鈕）；按鈕「儲存草稿」（名稱可帶快速鍵提示）與「送審」；
 *     儲存失敗時「儲存草稿」換成「重試儲存」；狀態字（頁面文字）：已儲存（HH:mm）／有未儲存的變更／儲存中…／儲存失敗：原因。
 *   - 離開提醒：role="dialog"，標題「要把修改存成草稿嗎？」，三鈕「繼續編輯」「不儲存，直接離開」（比對時忽略標點與空白）「儲存並離開」。
 *   - 刪除確認：role="alertdialog"，標題「刪除「名稱」？」，紅鈕「刪除旅程」、「取消」；成功導向 /journeys 並顯示「已刪除『名稱』」。
 *   - 時區 Asia/Taipei（同其他驗收）：草稿 updated_at 2026-11-02T01:41:00Z 顯示 09:41。
 */
import { expect } from 'vitest'
import {
  API, AMY, JID, REF_AMY, REF_DAMING, VIEWER, WANG, action, bodyText, callsTo, cleanup, deferred, dialog, expectAbsent, expectDisabled, expectEnabled,
  expectNotUsable, isDisabled, journeyBackend, journeySummary, json, mustAction, openJourney, page, reasonOf, resetEnv, review, brief,
  screen, start, teardown, userEvent, waitFor, within,
} from '../journey-review-ui/fixtures'
import { DRAFT_PATH, EDIT, draftBody, editorBackend, nodeButton, openEditor, selectNode, typeInto } from '../journey-canvas-ui/fixtures'

export {
  API, AMY, JID, REF_AMY, REF_DAMING, VIEWER, WANG, action, bodyText, callsTo, cleanup, deferred, dialog, expectAbsent, expectDisabled, expectEnabled, expectNotUsable,
  isDisabled, journeyBackend, journeySummary, json, mustAction, openJourney, page, reasonOf, resetEnv, review, brief, screen, start, teardown, userEvent, waitFor, within,
  DRAFT_PATH, EDIT, draftBody, editorBackend, nodeButton, openEditor, selectNode, typeInto,
}

export const JOURNEY_URL = `/journeys/${JID}`
/** 從未發佈（status DRAFT）的旅程詳情覆寫，搭配 journeyBackend({ detail }) */
export const NEVER = { status: 'DRAFT', latest_version: null, published_at: null, has_draft: true, versions: [] }

export const prob = (status: number, code: string, request_id: string) =>
  json({ type: 'about:blank', title: 'x', status, code, detail: 'd', request_id }, status)

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export const moreButton = () => document.querySelector<HTMLElement>('button[aria-label="更多動作"]')
export async function openMenu(): Promise<HTMLElement> {
  const b = moreButton()
  if (!b) throw new Error('找不到「更多動作」按鈕')
  if (b.getAttribute('aria-expanded') !== 'true') await userEvent.click(b)
  return screen.findByRole('menu')
}
export const menuItems = (menu: HTMLElement) => [...menu.querySelectorAll<HTMLElement>('[role="menuitem"]')]
export const itemNamed = (menu: HTMLElement, name: string) =>
  menuItems(menu).find((e) => (e.getAttribute('aria-label') ?? e.textContent ?? '').replace(/\s+/g, '').startsWith(name)) ?? null

/** 停用鈕／選單項引用的原因元素 id */
export const reasonIds = (el: Element) => (el.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean)

/** 儲存草稿（或儲存失敗後的「重試儲存」）按鈕 */
export const saveButton = () => screen.queryByRole('button', { name: /^(儲存草稿|重試儲存)/ })
export const mustSaveButton = () => {
  const b = saveButton()
  if (!b) throw new Error('找不到「儲存草稿」／「重試儲存」按鈕')
  return b
}
export const backControl = () => document.querySelector<HTMLElement>('[aria-label="返回旅程頁"]')

/** 在範本代碼欄打字，讓草稿有未儲存的變更（節點「VIP 專屬關心」） */
export async function makeDirty(value = 'cart_vip_x') {
  await selectNode('VIP 專屬關心')
  await typeInto('範本代碼', value)
  await waitFor(() => expect(bodyText()).toContain('有未儲存的變更'))
}
export const norm = (s: string | null | undefined) => (s ?? '').replace(/[\s，,、]/g, '')
export const leaveDialog = () => screen.findByRole('dialog', { name: '要把修改存成草稿嗎？' })
export const dlgButton = (d: HTMLElement, label: string) => {
  const hit = [...d.querySelectorAll('button')].find((b) => norm(b.getAttribute('aria-label') ?? b.textContent) === norm(label))
  if (!hit) throw new Error(`對話框沒有「${label}」；現有：${[...d.querySelectorAll('button')].map((b) => b.textContent).join('｜')}`)
  return hit
}
export const puts = (api: { calls: Array<{ method: string; path: string }> }) => api.calls.filter((c) => c.method === 'PUT' && c.path === DRAFT_PATH)
