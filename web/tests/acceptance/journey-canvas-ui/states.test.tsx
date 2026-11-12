// @vitest-environment jsdom
/** journey-canvas-ui：載入、5xx、草稿 404、檢視者 403 狀態；≤640px 工具列收合。 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DRAFT_PATH, EDIT, JID, bodyText, cleanup, deferred, draftBody, editorBackend, json, openEditor, problem, resetEnv, screen,
  start, teardown, user, userEvent, waitFor, within, NINE,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

describe('載入', () => {
  it('草稿載入中：role=status「載入草稿中」骨架；載入完成後消失、顯示工具列', async () => {
    const gate = deferred<Response>()
    editorBackend({ routes: { [`GET ${DRAFT_PATH}`]: () => gate.promise } })
    start(EDIT)
    expect(await screen.findByRole('status', { name: '載入草稿中' })).toBeTruthy()
    expect(screen.queryByRole('toolbar', { name: '加入節點' })).toBeNull()
    gate.resolve(json(draftBody()))
    await screen.findByRole('toolbar', { name: '加入節點' })
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
    await screen.findByRole('toolbar', { name: '加入節點' })
  })

  it('草稿 404（DRAFT_NOT_FOUND）：「這個旅程目前沒有草稿」＋說明＋「看目前上線的版本」連到旅程頁；沒有工具列', async () => {
    editorBackend({ routes: { [`GET ${DRAFT_PATH}`]: () => problem(404, 'DRAFT_NOT_FOUND') } })
    start(EDIT)
    expect(await screen.findByText('這個旅程目前沒有草稿。')).toBeTruthy()
    expect(bodyText()).toContain('草稿可能已被送審核准，或已被捨棄')
    const link = screen.getByRole('link', { name: '看目前上線的版本' })
    expect(link.getAttribute('href')).toBe(`/journeys/${JID}`)
    expect(screen.queryByRole('toolbar', { name: '加入節點' })).toBeNull()
  })

  it('檢視者進編輯網址：整頁「你的角色（檢視者）不能編輯旅程」＋「回到旅程頁」；沒有工具列、不送 PUT', async () => {
    const ed = editorBackend({ me: user({ role: 'VIEWER', display_name: '林小雅' }) })
    start(EDIT)
    expect(await screen.findByText(/你的角色（檢視者）不能編輯旅程/)).toBeTruthy()
    expect(bodyText()).toContain('可以查看旅程與各節點人數')
    const link = screen.getByRole('link', { name: '回到旅程頁' })
    expect(link.getAttribute('href')).toBe(`/journeys/${JID}`)
    expect(screen.queryByRole('toolbar', { name: '加入節點' })).toBeNull()
    await new Promise((r) => setTimeout(r, 800))
    expect(ed.puts().length).toBe(0)
  })

  it('後端對草稿回 403 FORBIDDEN 時也顯示無權限（不顯示畫布）', async () => {
    editorBackend({ me: user({ role: 'VIEWER' }), routes: { [`GET ${DRAFT_PATH}`]: () => problem(403, 'FORBIDDEN') } })
    start(EDIT)
    await waitFor(() => expect(bodyText()).toMatch(/沒有權限|不能編輯/))
    expect(screen.queryByRole('toolbar', { name: '加入節點' })).toBeNull()
  })
})

describe('窄螢幕（<= 640px）：工具列收合為「新增節點」', () => {
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

  it('DOM：完整工具列（.tb-full）＋ details.addmenu「新增節點」內含九個節點按鈕', async () => {
    editorBackend()
    await openEditor()
    const summary = [...document.querySelectorAll('details.addmenu > summary')].find((s) => s.textContent?.includes('新增節點'))
    expect(summary, 'details.addmenu 的 summary 要有「新增節點」').toBeTruthy()
    const menu = summary!.parentElement as HTMLElement
    for (const n of NINE) expect(within(menu).getByRole('button', { name: n })).toBeTruthy()
    expect(document.querySelector('.tb-full[role="toolbar"]')).toBeTruthy()
  })

  it('app.css：<= 640px 隱藏 .tb-full、顯示 .addmenu，且 summary 高度 >= 44px', () => {
    const n = narrow()
    expect(n).toMatch(/\.tb-full\s*\{[^}]*display:\s*none/)
    expect(n).toMatch(/\.addmenu\s*\{[^}]*display:\s*block/)
    expect(n).toMatch(/\.addmenu\s*>\s*summary\s*\{[^}]*min-height:\s*44px/)
  })

  it('選單內按「等事件」會加一個節點', async () => {
    editorBackend()
    await openEditor()
    const menu = [...document.querySelectorAll('details.addmenu')][0] as HTMLElement
    const before = document.querySelectorAll('button[aria-pressed]').length
    await userEvent.click(within(menu).getByRole('button', { name: '等事件' }))
    await waitFor(() => expect(document.querySelectorAll('button[aria-pressed]').length).toBeGreaterThan(before))
  })
})
