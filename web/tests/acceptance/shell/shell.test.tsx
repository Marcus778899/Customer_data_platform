// @vitest-environment jsdom
/**
 * app-shell-login 驗收測試：外框、受眾佔位、403 無權限（UI：外框、受眾佔位、無權限、窄螢幕與無障礙）
 * 對應驗收條件：
 *   「外框：桌面左側欄、< 1024px 頂列＋選單；帳號區顯示姓名、角色、修改密碼、登出；無工作區切換」
 *   「受眾佔位寫『預計 v0.4.0』；403 整頁且導覽可用」
 *   「無障礙依 UI 規格（焦點、role=alert／status、鍵盤可操作）」
 *
 * jsdom 不做排版也不評估 media query：「桌面側欄／窄螢幕頂列」以 DOM 結構（兩處導覽、選單鈕）加上
 * 解析 web/src/styles/*.css 的 media 規則驗證（同 narrow-layout 測試的作法）；真實排版仍須 qa 以瀏覽器實測 375／800／1280px。
 * 對 worker 的假設：結構沿用設計稿 index.html（側欄 .side 與窄螢幕頂列 .top 並存，頂列內含「選單」鈕與同樣的導覽、帳號區）。
 */
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ROLE_LABEL, mockApi, problem, resetEnv, start, teardown, text, user, type Role } from './helpers'

beforeEach(resetEnv)
afterEach(teardown)

async function shell(role: Role = 'EDITOR', name = '陳小美', path = '/') {
  const api = mockApi({ me: user({ role, display_name: name }) })
  start(path)
  await screen.findAllByRole('navigation', { name: /主要導覽/ })
  return api
}
const link = (name: RegExp | string) => screen.getAllByRole('link', { name })[0]

describe('外框：導覽與帳號區', () => {
  it('導覽「主要導覽」有 旅程、客戶、受眾（附「規劃中」標示）三項，順序固定；沒有工作區切換', async () => {
    await shell()
    const nav = screen.getAllByRole('navigation', { name: /主要導覽/ })[0]
    const items = within(nav).getAllByRole('link').map((a) => a.textContent?.trim())
    expect(items).toEqual(['旅程', '客戶', '受眾規劃中'])
    expect(text()).not.toMatch(/工作區|租戶|workspace/i)
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  it('產品名「客戶旅程平台」顯示在外框（側欄與窄螢幕頂列）', async () => {
    await shell()
    expect(screen.getAllByText('客戶旅程平台').length).toBeGreaterThanOrEqual(1)
    expect(document.title === '' || document.title.includes('客戶旅程平台')).toBe(true)
  })

  it.each([
    ['VIEWER', '周小華'],
    ['EDITOR', '陳小美'],
    ['ADMIN', '林大明'],
  ] as const)('帳號區（%s）：顯示「已登入：姓名」「角色：中文角色」、修改密碼、登出', async (role, name) => {
    await shell(role, name)
    expect(text()).toContain(`已登入：${name}`)
    expect(text()).toContain(`角色：${ROLE_LABEL[role]}`)
    expect(link('修改密碼')).toBeTruthy()
    expect(screen.getAllByRole('button', { name: '登出' }).length + screen.queryAllByRole('link', { name: '登出' }).length).toBeGreaterThan(0)
  })

  it('目前頁面的導覽項標 aria-current="page"（旅程）', async () => {
    await shell()
    expect(screen.getAllByRole('link', { name: '旅程' }).some((a) => a.getAttribute('aria-current') === 'page')).toBe(true)
    expect(screen.getAllByRole('link', { name: /^受眾/ }).every((a) => a.getAttribute('aria-current') !== 'page')).toBe(true)
  })

  it('登出：POST /auth/logout 後回登入頁，不顯示逾時提示', async () => {
    const api = await shell()
    const u = userEvent.setup()
    const btn = [...screen.queryAllByRole('button', { name: '登出' }), ...screen.queryAllByRole('link', { name: '登出' })][0]
    await u.click(btn)
    await screen.findByLabelText('Email')
    expect(api.count('POST', '/api/v1/auth/logout')).toHaveLength(1)
    expect(window.location.pathname).toBe('/login')
    expect(text()).not.toContain('登入已逾時')
  })
})

describe('外框：窄螢幕與無障礙', () => {
  it('窄螢幕頂列有「選單」鈕（<details><summary> 或 aria-expanded 按鈕），展開區含同樣的導覽與帳號區', async () => {
    await shell()
    const toggle = [...document.querySelectorAll('summary, button[aria-expanded]')].find((e) => e.textContent?.trim() === '選單')
    expect(toggle, '找不到「選單」鈕').toBeTruthy()
    const navs = screen.getAllByRole('navigation', { name: /主要導覽/ })
    expect(navs.length).toBeGreaterThanOrEqual(2)
    for (const n of navs) {
      expect(within(n).getAllByRole('link').map((a) => a.textContent?.trim())).toEqual(['旅程', '客戶', '受眾規劃中'])
    }
    expect(screen.getAllByRole('link', { name: '修改密碼' }).length).toBeGreaterThanOrEqual(2)
  })

  it('兩個導覽 landmark 的名稱不同（「主要導覽」與「主要導覽（窄螢幕）」），讀屏可區分', async () => {
    await shell()
    const labels = screen.getAllByRole('navigation').map((n) => n.getAttribute('aria-label'))
    const main = labels.filter((l) => l?.startsWith('主要導覽'))
    expect(new Set(main).size).toBe(main.length)
    expect(main.length).toBeGreaterThanOrEqual(2)
  })

  it('跳到主要內容：skip link 指向唯一的 <main tabindex="-1">，且 main 內有 h1', async () => {
    await shell()
    const skip = screen.getAllByRole('link', { name: '跳到主要內容' })[0] as HTMLAnchorElement
    const id = skip.getAttribute('href')!.slice(1)
    const main = document.getElementById(id)!
    expect(main.tagName).toBe('MAIN')
    expect(main.getAttribute('tabindex')).toBe('-1')
    expect(document.querySelectorAll('main')).toHaveLength(1)
    await waitFor(() => expect(within(main).getAllByRole('heading', { level: 1 })).toHaveLength(1))
  })

  it('導覽項與帳號區操作皆為可聚焦的原生連結／按鈕（鍵盤可操作）', async () => {
    await shell()
    for (const el of [
      ...screen.getAllByRole('link', { name: /^(旅程|客戶|受眾)/ }),
      ...screen.getAllByRole('link', { name: '修改密碼' }),
    ]) {
      expect(el.tagName).toBe('A')
      expect(el.getAttribute('href')).toBeTruthy()
      expect(el.getAttribute('tabindex')).not.toBe('-1')
    }
    for (const el of screen.queryAllByRole('button', { name: '登出' })) expect(el.getAttribute('tabindex')).not.toBe('-1')
  })

  describe('樣式規則（解析 web/src/styles/*.css）', () => {
    const dir = resolve(dirname(fileURLToPath(import.meta.url)), '../../../src/styles')
    const css = readdirSync(dir)
      .filter((f) => f.endsWith('.css'))
      .map((f) => readFileSync(resolve(dir, f), 'utf8'))
      .join('\n')
      .replace(/\/\*[\s\S]*?\*\//g, '')
    /** 取出所有 @media (max-width: Npx) 區塊內容，依 N 分組 */
    const medias: { px: number; body: string }[] = []
    for (const m of css.matchAll(/@media[^{]*max-width:\s*(\d+)px[^{]*\{/g)) {
      let depth = 1
      let i = m.index! + m[0].length
      const from = i
      while (i < css.length && depth > 0) {
        if (css[i] === '{') depth++
        else if (css[i] === '}') depth--
        i++
      }
      medias.push({ px: Number(m[1]), body: css.slice(from, i - 1) })
    }

    it('有 1024px 以下（1023 或 1024）的 media 區塊，內含隱藏桌面側欄（display:none）的規則', () => {
      const b = medias.filter((m) => m.px === 1023 || m.px === 1024)
      expect(b.length, '缺 max-width:1023/1024px 區塊').toBeGreaterThan(0)
      expect(b.some((m) => /display:\s*none/.test(m.body))).toBe(true)
    })

    it('1024px 以下的選單鈕、導覽項、修改密碼、登出高度 >= 44px（區塊內出現 44px 的 min-height／height）', () => {
      const b = medias.filter((m) => m.px >= 640 && m.px <= 1024)
      expect(b.some((m) => /(min-)?height:\s*(4[4-9]|[5-9]\d)px|(min-)?height:\s*var\(--touch[\w-]*\)/.test(m.body))).toBe(true)
    })

    it('不使用固定寬度撐出橫向捲動：640px 以下規則不設超過 375px 的 width／min-width', () => {
      for (const m of medias.filter((x) => x.px <= 640)) {
        for (const w of m.body.matchAll(/(?:^|[;{\s])(?:min-)?width:\s*(\d+)px/g)) expect(Number(w[1])).toBeLessThanOrEqual(375)
      }
    })

    it('尊重 prefers-reduced-motion（有對應 media 區塊）', () => {
      expect(css).toMatch(/@media[^{]*prefers-reduced-motion/)
    })
  })
})

describe('受眾佔位（D-034、D-049）', () => {
  async function openAudience() {
    await shell()
    const u = userEvent.setup()
    await u.click(link(/^受眾/))
    await screen.findByRole('heading', { level: 1, name: /受眾/ })
    return u
  }

  it('導覽點「受眾」→ 佔位頁：標題「受眾」＋「規劃中」徽章、「受眾功能預計在 v0.4.0 提供…」、「目前請由「旅程」與「客戶」使用平台。」', async () => {
    await openAudience()
    const h1 = screen.getByRole('heading', { level: 1 })
    expect(h1.textContent).toContain('受眾')
    expect(h1.textContent).toContain('規劃中')
    expect(text()).toContain('受眾功能預計在 v0.4.0 提供，屆時可在這裡建立客戶分群。')
    expect(text()).toContain('目前請由「旅程」與「客戶」使用平台。')
    expect(window.location.pathname).not.toBe('/login')
  })

  it('導覽的「受眾」標 aria-current="page"，外框（帳號區、導覽）仍在；不發任何受眾相關 API', async () => {
    const api = mockApi({ me: user() })
    start('/')
    await screen.findAllByRole('navigation', { name: /主要導覽/ })
    const before = api.calls.length
    const u = userEvent.setup()
    await u.click(link(/^受眾/))
    await screen.findByRole('heading', { level: 1, name: /受眾/ })
    expect(screen.getAllByRole('link', { name: /^受眾/ }).some((a) => a.getAttribute('aria-current') === 'page')).toBe(true)
    expect(text()).toContain('已登入：陳小美')
    expect(api.calls.slice(before).filter((c) => /audience/i.test(c.path))).toHaveLength(0)
  })

  it('重新整理（直接開受眾頁的網址）仍顯示佔位頁，不是「找不到這個頁面」', async () => {
    await openAudience()
    const path = window.location.pathname
    teardown()
    mockApi({ me: user() })
    start(path)
    await screen.findByRole('heading', { level: 1, name: /受眾/ })
    expect(text()).toContain('預計在 v0.4.0')
    expect(text()).not.toContain('找不到這個頁面')
  })

  it('從受眾回到「旅程」導覽可用', async () => {
    const u = await openAudience()
    await u.click(link('旅程'))
    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: /受眾/ })).toBeNull())
    expect(screen.getAllByRole('link', { name: '旅程' }).some((a) => a.getAttribute('aria-current') === 'page')).toBe(true)
  })
})

describe('403 FORBIDDEN 無權限（整頁、導覽可用）', () => {
  const forbid = () => problem(403, 'FORBIDDEN')
  const forbidden = {
    'GET /api/v1/enrollments': forbid,
    'GET /api/v1/journeys': forbid,
    'GET /api/v1/profiles': forbid,
  }

  it('資料請求 403 → 整頁「你沒有權限使用這個功能」＋角色說明＋「回旅程列表」，role=alert，不導向登入', async () => {
    mockApi({ me: user({ role: 'VIEWER', display_name: '周小華' }), routes: forbidden })
    start('/?q=abc')
    const h1 = await screen.findByRole('heading', { level: 1, name: '你沒有權限使用這個功能' })
    expect(h1.closest('[role="alert"]')).toBeTruthy()
    expect(text()).toContain('目前帳號「周小華」的角色是檢視者，只能檢視資料。')
    expect(text()).toContain('如需編輯權限，請聯絡管理員。')
    expect(screen.getByRole('link', { name: '回旅程列表' })).toBeTruthy()
    expect(window.location.pathname).not.toBe('/login')
  })

  it('403 頁上外框與導覽仍可用：能點進「受眾」，403 畫面隨之消失', async () => {
    mockApi({ me: user({ role: 'VIEWER', display_name: '周小華' }), routes: forbidden })
    start('/?q=abc')
    await screen.findByRole('heading', { level: 1, name: '你沒有權限使用這個功能' })
    expect(screen.getAllByRole('navigation', { name: /主要導覽/ }).length).toBeGreaterThan(0)
    expect(text()).toContain('已登入：周小華')
    const u = userEvent.setup()
    await u.click(link(/^受眾/))
    await screen.findByRole('heading', { level: 1, name: /受眾/ })
    expect(screen.queryByText('你沒有權限使用這個功能')).toBeNull()
  })

  it('403 頁的 h1 只有一個，且頁面只有一個 <main>', async () => {
    mockApi({ me: user({ role: 'VIEWER', display_name: '周小華' }), routes: forbidden })
    start('/?q=abc')
    await screen.findByRole('heading', { level: 1, name: '你沒有權限使用這個功能' })
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(document.querySelectorAll('main')).toHaveLength(1)
  })
})
