// @vitest-environment jsdom
/**
 * app-shell-login 驗收測試：登入（auth.md §3；UI：登入畫面、狀態表「登入」列、無障礙）
 * 對應驗收條件：「登入…的空／載入／錯誤／成功狀態與文案依 UI 規格；401 單一句、429 依 Retry-After 倒數、到 0 恢復」
 * 與「無障礙依 UI 規格（role=alert／status、鍵盤可操作）」。
 * 429 倒數用真實計時器、Retry-After 取短值驗證恢復；jsdom 無法驗證讀屏，只驗「朗讀內容」不隨倒數改變。
 */
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { announced, mockApi, noContent, problem, resetEnv, start, teardown, text, user } from './helpers'

beforeEach(resetEnv)
afterEach(teardown)

async function fill(u: ReturnType<typeof userEvent.setup>, email: string, pw: string) {
  const em = await screen.findByLabelText('Email')
  if (email) await u.type(em, email)
  if (pw) await u.type(screen.getByLabelText('密碼'), pw)
}
const loginBtn = () => screen.getByRole('button', { name: '登入' })
const isDisabled = (b: HTMLElement) => b.getAttribute('aria-disabled') === 'true' || (b as HTMLButtonElement).disabled
const NUM = /\d+\s*分\s*\d+\s*秒|\d+\s*秒/g
const secondsOf = (s: string) => {
  const m = /(?:(\d+)\s*分\s*)?(\d+)\s*秒/.exec(s)
  if (!m) throw new Error('找不到倒數文字：' + s)
  return Number(m[1] ?? 0) * 60 + Number(m[2])
}

describe('登入：初始與成功', () => {
  it('初始：標題、說明、Email／密碼欄、「登入」鈕、form 有名稱「登入」', async () => {
    mockApi({ me: null })
    start('/login')
    expect(await screen.findByRole('heading', { level: 1, name: '客戶旅程平台' })).toBeTruthy()
    expect(text()).toContain('請以個人帳號登入。')
    const em = screen.getByLabelText('Email') as HTMLInputElement
    const pw = screen.getByLabelText('密碼') as HTMLInputElement
    expect(em.type).toBe('email')
    expect(em.autocomplete).toBe('username')
    expect(pw.type).toBe('password')
    expect(pw.autocomplete).toBe('current-password')
    expect(em.value).toBe('')
    expect(screen.getByRole('form', { name: '登入' })).toBeTruthy()
    expect(loginBtn()).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('未登入進站（/me 401）→ 導向 /login，沒有逾時或錯誤提示', async () => {
    mockApi({ me: null })
    start('/')
    await screen.findByLabelText('Email')
    expect(window.location.pathname).toBe('/login')
    expect(text()).not.toContain('登入已逾時')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('送出 POST /auth/login 帶 { email, password } JSON；成功後先呼叫 GET /auth/me 再回原頁（?next）', async () => {
    const api = mockApi({ me: null })
    const u = userEvent.setup()
    start('/?q=abc')
    await fill(u, 'Amy.Chen@Example.com', 'correct-horse-battery')
    await u.click(loginBtn())
    await waitFor(() => expect(window.location.pathname).not.toBe('/login'))
    expect(window.location.pathname).toBe('/')
    expect(window.location.search).toContain('q=abc')
    const login = api.count('POST', '/api/v1/auth/login')
    expect(login).toHaveLength(1)
    expect(login[0].body).toEqual({ email: 'Amy.Chen@Example.com', password: 'correct-horse-battery' })
    expect(login[0].headers['content-type']).toContain('application/json')
    const iLogin = api.calls.indexOf(login[0])
    const iMeAfter = api.calls.findIndex((c, i) => i > iLogin && c.method === 'GET' && c.path === '/api/v1/auth/me')
    expect(iMeAfter).toBeGreaterThan(iLogin)
  })

  it('鍵盤可操作：Email → Tab → 密碼 → Enter 即可送出', async () => {
    const api = mockApi({ me: null })
    const u = userEvent.setup()
    start('/login')
    await u.type(await screen.findByLabelText('Email'), 'amy.chen@example.com')
    await u.tab()
    expect(document.activeElement).toBe(screen.getByLabelText('密碼'))
    await u.keyboard('correct-horse-battery{Enter}')
    await waitFor(() => expect(api.count('POST', '/api/v1/auth/login')).toHaveLength(1))
  })

  it('登入成功後，網址回到 /（無 next 時），不再顯示登入頁', async () => {
    mockApi({ me: null })
    const u = userEvent.setup()
    start('/login')
    await fill(u, 'amy.chen@example.com', 'correct-horse-battery')
    await u.click(loginBtn())
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(screen.queryByLabelText('Email')).toBeNull()
  })
})

describe('登入：空欄位（400 VALIDATION_FAILED）', () => {
  const badRequest = () => problem(400, 'VALIDATION_FAILED')

  it('兩欄皆空：欄位下「請輸入 Email。」「請輸入密碼。」，aria-invalid 並以 aria-describedby 關聯', async () => {
    mockApi({ me: null, routes: { 'POST /api/v1/auth/login': badRequest } })
    const u = userEvent.setup()
    start('/login')
    await screen.findByLabelText('Email')
    await u.click(loginBtn())
    const em = screen.getByLabelText('Email')
    const pw = screen.getByLabelText('密碼')
    await waitFor(() => expect(em.getAttribute('aria-invalid')).toBe('true'))
    expect(pw.getAttribute('aria-invalid')).toBe('true')
    const describedText = (el: HTMLElement) =>
      (el.getAttribute('aria-describedby') ?? '').split(/\s+/).map((id) => document.getElementById(id)?.textContent ?? '').join(' ')
    expect(describedText(em)).toContain('請輸入 Email。')
    expect(describedText(pw)).toContain('請輸入密碼。')
    expect(window.location.pathname).toBe('/login')
  })

  it('只有 Email 空：只標 Email 欄', async () => {
    mockApi({ me: null, routes: { 'POST /api/v1/auth/login': badRequest } })
    const u = userEvent.setup()
    start('/login')
    await fill(u, '', 'correct-horse-battery')
    await u.click(loginBtn())
    await waitFor(() => expect(screen.getByLabelText('Email').getAttribute('aria-invalid')).toBe('true'))
    expect(text()).toContain('請輸入 Email。')
    expect(text()).not.toContain('請輸入密碼。')
    expect(screen.getByLabelText('密碼').getAttribute('aria-invalid')).not.toBe('true')
  })
})

describe('登入：載入中', () => {
  it('送出中：欄位唯讀、按鈕「登入中…」且不可再送、form aria-busy', async () => {
    let release: (r: Response) => void = () => {}
    const pending = new Promise<Response>((res) => (release = res))
    const api = mockApi({ me: null, routes: { 'POST /api/v1/auth/login': () => pending } })
    const u = userEvent.setup()
    start('/login')
    await fill(u, 'amy.chen@example.com', 'correct-horse-battery')
    await u.click(loginBtn())
    const btn = await screen.findByRole('button', { name: '登入中…' })
    expect(isDisabled(btn)).toBe(true)
    expect((screen.getByLabelText('Email') as HTMLInputElement).readOnly).toBe(true)
    expect((screen.getByLabelText('密碼') as HTMLInputElement).readOnly).toBe(true)
    expect(screen.getByRole('form', { name: '登入' }).getAttribute('aria-busy')).toBe('true')
    await u.click(btn)
    await u.keyboard('{Enter}')
    expect(api.count('POST', '/api/v1/auth/login')).toHaveLength(1)
    await act(async () => {
      api.me = api.loginAs
      release(noContent())
    })
    await waitFor(() => expect(window.location.pathname).toBe('/'))
  })
})

describe('登入：401 INVALID_CREDENTIALS（唯一一句）', () => {
  const bad = () => problem(401, 'INVALID_CREDENTIALS')

  it('role=alert 只顯示「Email 或密碼不正確。」；Email 保留、密碼清空；不洩漏帳號是否存在', async () => {
    mockApi({ me: null, routes: { 'POST /api/v1/auth/login': bad } })
    const u = userEvent.setup()
    start('/login')
    await fill(u, 'amy.chen@example.com', 'wrong-password-1')
    await u.click(loginBtn())
    const alert = await screen.findByRole('alert')
    expect(alert.textContent?.trim()).toBe('Email 或密碼不正確。')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect((screen.getByLabelText('Email') as HTMLInputElement).value).toBe('amy.chen@example.com')
    expect((screen.getByLabelText('密碼') as HTMLInputElement).value).toBe('')
    expect(text()).not.toMatch(/不存在|停用|已鎖定/)
    expect(window.location.pathname).toBe('/login')
    expect(isDisabled(loginBtn())).toBe(false)
  })

  it('錯誤後再以正確密碼登入成功，錯誤訊息消失', async () => {
    let ok = false
    const api = mockApi({
      me: null,
      routes: {
        'POST /api/v1/auth/login': () => {
          if (!ok) return bad()
          api.me = api.loginAs
          return noContent()
        },
      },
    })
    const u = userEvent.setup()
    start('/login')
    await fill(u, 'amy.chen@example.com', 'wrong-password-1')
    await u.click(loginBtn())
    await screen.findByRole('alert')
    ok = true
    await u.type(screen.getByLabelText('密碼'), 'correct-horse-battery')
    await u.click(loginBtn())
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('登入：429 LOGIN_RATE_LIMITED（D-045）', () => {
  const limited = (sec: number) => () => problem(429, 'LOGIN_RATE_LIMITED', { 'Retry-After': String(sec) })

  it('role=alert 顯示說明與依 Retry-After 的「N 分 N 秒」，按鈕改「請稍後再試」且不可用', async () => {
    mockApi({ me: null, routes: { 'POST /api/v1/auth/login': limited(750) } })
    const u = userEvent.setup()
    start('/login')
    await fill(u, 'amy.chen@example.com', 'correct-horse-battery')
    await u.click(loginBtn())
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('登入失敗次數過多，這個 Email 暫時無法登入。')
    expect(alert.textContent).toMatch(/請在\s*12\s*分\s*(30|29)\s*秒\s*後再試/)
    expect(isDisabled(screen.getByRole('button', { name: '請稍後再試' }))).toBe(true)
    expect(screen.queryByRole('button', { name: '登入' })).toBeNull()
  })

  it('鎖定中再按（或 Enter）不會送出請求（不論密碼是否正確）', async () => {
    const api = mockApi({ me: null, routes: { 'POST /api/v1/auth/login': limited(600) } })
    const u = userEvent.setup()
    start('/login')
    await fill(u, 'amy.chen@example.com', 'correct-horse-battery')
    await u.click(loginBtn())
    const btn = await screen.findByRole('button', { name: '請稍後再試' })
    await u.click(btn)
    await u.click(screen.getByLabelText('密碼'))
    await u.keyboard('{Enter}')
    expect(api.count('POST', '/api/v1/auth/login')).toHaveLength(1)
  })

  it('每秒遞減；朗讀內容（排除 aria-hidden）不隨倒數改變；到 0 按鈕恢復為「登入」並以 role=status 朗讀「現在可以再試一次」', async () => {
    mockApi({ me: null, routes: { 'POST /api/v1/auth/login': limited(3) } })
    const u = userEvent.setup()
    start('/login')
    await fill(u, 'amy.chen@example.com', 'correct-horse-battery')
    await u.click(loginBtn())
    const alert = await screen.findByRole('alert')
    const first = secondsOf(alert.textContent ?? '')
    expect(first).toBeGreaterThanOrEqual(2)
    expect(first).toBeLessThanOrEqual(3)
    const spoken = announced(alert).replace(NUM, '#')
    await waitFor(() => expect(secondsOf(screen.getByRole('alert').textContent ?? '')).toBeLessThan(first), { timeout: 2500 })
    expect(announced(screen.getByRole('alert')).replace(NUM, '#')).toBe(spoken)
    await waitFor(() => expect(screen.getByRole('button', { name: '登入' })).toBeTruthy(), { timeout: 4500 })
    expect(isDisabled(loginBtn())).toBe(false)
    expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('現在可以再試一次'))).toBe(true)
  }, 12000)

  it('恢復後可再試：再送一次登入（第二次成功）', async () => {
    let n = 0
    const api = mockApi({
      me: null,
      routes: {
        'POST /api/v1/auth/login': () => {
          if (++n === 1) return limited(2)()
          api.me = api.loginAs
          return noContent()
        },
      },
    })
    const u = userEvent.setup()
    start('/login')
    await fill(u, 'amy.chen@example.com', 'correct-horse-battery')
    await u.click(loginBtn())
    await screen.findByRole('button', { name: '請稍後再試' })
    await waitFor(() => expect(screen.getByRole('button', { name: '登入' })).toBeTruthy(), { timeout: 4000 })
    await u.click(loginBtn())
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(n).toBe(2)
  }, 12000)
})

describe('登入：逾時導回（401 UNAUTHENTICATED）', () => {
  const expire = () => problem(401, 'UNAUTHENTICATED')
  const expiring = {
    'GET /api/v1/enrollments': expire,
    'GET /api/v1/journeys': expire,
    'GET /api/v1/profiles': expire,
  }

  it('已登入後資料請求 401：導向 /login，role=status 顯示「登入已逾時，請重新登入。登入後會回到原本的頁面。」，登入後回原頁', async () => {
    const api = mockApi({ me: user(), routes: expiring })
    start('/?q=abc')
    await screen.findByLabelText('Email')
    expect(window.location.pathname).toBe('/login')
    const status = screen.getAllByRole('status').find((s) => s.textContent?.includes('登入已逾時'))
    expect(status?.textContent).toContain('登入已逾時，請重新登入。')
    expect(status?.textContent).toContain('登入後會回到原本的頁面。')
    expect(screen.queryByRole('alert')).toBeNull()
    api.me = null
    const u = userEvent.setup()
    await fill(u, 'amy.chen@example.com', 'correct-horse-battery')
    await u.click(loginBtn())
    await waitFor(() => expect(window.location.search).toContain('q=abc'))
    expect(window.location.pathname).toBe('/')
  })

  it('直接開 /login 不顯示逾時提示', async () => {
    mockApi({ me: null })
    start('/login')
    await screen.findByLabelText('Email')
    expect(text()).not.toContain('登入已逾時')
  })

  it('逾時後輸入錯誤密碼：只顯示 401 單一句，逾時提示消失', async () => {
    mockApi({ me: user(), routes: { ...expiring, 'POST /api/v1/auth/login': () => problem(401, 'INVALID_CREDENTIALS') } })
    const u = userEvent.setup()
    start('/?q=abc')
    await screen.findByLabelText('Email')
    await u.type(screen.getByLabelText('Email'), 'amy.chen@example.com')
    await u.type(screen.getByLabelText('密碼'), 'wrong-password-1')
    await u.click(loginBtn())
    await waitFor(() => expect(text()).toContain('Email 或密碼不正確。'))
    expect(text()).not.toContain('登入已逾時')
  })
})
