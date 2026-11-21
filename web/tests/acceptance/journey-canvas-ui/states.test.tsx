// @vitest-environment jsdom
/** journey-canvas-ui：載入、5xx、草稿 404、檢視者 403 狀態；≤640px 節點庫（canvas-connect-drag）。 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DRAFT_PATH, EDIT, JID, bodyText, cleanup, deferred, draftBody, editorBackend, json, openEditor, problem, resetEnv, screen,
  start, teardown, user, userEvent, waitFor, within, NINE, libraryItem,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

describe('載入', () => {
  it('草稿載入中：role=status「載入草稿中」骨架；載入完成後消失、顯示工具列', async () => {
    const gate = deferred<Response>()
    editorBackend({ routes: { [`GET ${DRAFT_PATH}`]: () => gate.promise } })
    start(EDIT)
    expect(await screen.findByRole('status', { name: '載入草稿中' })).toBeTruthy()
    expect(screen.queryByRole('complementary', { name: '節點庫' })).toBeNull()
    gate.resolve(json(draftBody()))
    await screen.findByRole('complementary', { name: '節點庫' })
    await waitFor(() => expect(screen.queryByRole('status', { name: '載入草稿中' })).toBeNull())
  })
})

describe('錯誤與權限', () => {
  it('5xx：role=alert「暫時無法載入草稿」＋請求編號＋「重試」；重試後載入成功', async () => {
    let fail = true
    editorBackend({
      routes: {
        [`GET ${DRAFT_PATH}`]: () =>
          fail
            ? json({ type: 'about:blank', title: 'x', status: 503, code: 'UNAVAILABLE', detail: 'd', request_id: 'REQ-LOAD-503' }, 503)
            : json(draftBody()),
      },
    })
    start(EDIT)
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('暫時無法載入草稿')
    expect(alert.textContent).toContain('REQ-LOAD-503')
    fail = false
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await screen.findByRole('complementary', { name: '節點庫' })
  })

  it('草稿 404（DRAFT_NOT_FOUND）：「這個旅程目前沒有草稿」＋說明＋「看目前上線的版本」連到旅程頁；沒有工具列', async () => {
    editorBackend({ routes: { [`GET ${DRAFT_PATH}`]: () => problem(404, 'DRAFT_NOT_FOUND') } })
    start(EDIT)
    expect(await screen.findByText('這個旅程目前沒有草稿。')).toBeTruthy()
    expect(bodyText()).toContain('草稿可能已被送審核准，或已被捨棄')
    const link = screen.getByRole('link', { name: '看目前上線的版本' })
    expect(link.getAttribute('href')).toBe(`/journeys/${JID}`)
    expect(screen.queryByRole('complementary', { name: '節點庫' })).toBeNull()
  })

  it('檢視者進編輯網址：整頁「你的角色（檢視者）不能編輯旅程」＋「回到旅程頁」；沒有工具列、不送 PUT', async () => {
    const ed = editorBackend({ me: user({ role: 'VIEWER', display_name: '林小雅' }) })
    start(EDIT)
    expect(await screen.findByText(/你的角色（檢視者）不能編輯旅程/)).toBeTruthy()
    expect(bodyText()).toContain('可以查看旅程與各節點人數')
    const link = screen.getByRole('link', { name: '回到旅程頁' })
    expect(link.getAttribute('href')).toBe(`/journeys/${JID}`)
    expect(screen.queryByRole('complementary', { name: '節點庫' })).toBeNull()
    await new Promise((r) => setTimeout(r, 800))
    expect(ed.puts().length).toBe(0)
  })

  it('後端對草稿回 403 FORBIDDEN 時也顯示無權限（不顯示畫布）', async () => {
    editorBackend({ me: user({ role: 'VIEWER' }), routes: { [`GET ${DRAFT_PATH}`]: () => problem(403, 'FORBIDDEN') } })
    start(EDIT)
    await waitFor(() => expect(bodyText()).toMatch(/沒有權限|不能編輯/))
    expect(screen.queryByRole('complementary', { name: '節點庫' })).toBeNull()
  })
})

describe('窄螢幕（<= 640px）：節點庫變上方橫排（點一下新增，不支援拖入）', () => {
  const here = dirname(fileURLToPath(import.meta.url))
  const css = readFileSync(resolve(here, '../../../src/styles/app.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
  function narrow(): string {
    const out: string[] = []
    const re = /@media[^{]*max-width:\s*(\d+)px[^{]*\{/g
    let m: RegExpExecArray | null
    while ((m = re.exec(css))) {
      let depth = 1
      let i = re.lastIndex
      while (i < css.length && depth > 0) { depth += css[i] === '{' ? 1 : css[i] === '}' ? -1 : 0; i++ }
      if (Number(m[1]) <= 640) out.push(css.slice(re.lastIndex, i - 1))
    }
    return out.join('\n')
  }

  it('DOM：節點庫 aside.nodelib 內含九個節點按鈕（窄螢幕只是 CSS 改成橫排，同一份元素）；沒有 details.addmenu', async () => {
    editorBackend()
    const lib = await openEditor()
    expect(lib.classList.contains('nodelib')).toBe(true)
    for (const n of NINE) expect(libraryItem(lib, n)).toBeTruthy()
    expect(document.querySelector('details.addmenu')).toBeNull()
  })

  it('app.css：<= 640px 的 .nodelib 橫排（flex row）、可橫向捲動、項目高度 >= 44px', () => {
    const n = narrow()
    expect(n).toMatch(/\.nodelib\s*\{[^}]*(flex-direction:\s*row|display:\s*flex)/)
    expect(n).toMatch(/\.nodelib\s*\{[^}]*overflow-x:\s*(auto|scroll)/)
    expect(n).toMatch(/\.nodelib[^{]*\{[^}]*min-height:\s*44px/)
  })

  it('節點庫內按「等事件」會加一個節點', async () => {
    editorBackend()
    const lib = await openEditor()
    const before = document.querySelectorAll('button[aria-pressed]').length
    await userEvent.click(libraryItem(lib, '等事件'))
    await waitFor(() => expect(document.querySelectorAll('button[aria-pressed]').length).toBeGreaterThan(before))
  })
})
