// @vitest-environment jsdom
/**
 * app-shell-login 驗收測試：CSRF（auth.md §1.1）
 * 對應驗收條件：「寫入請求帶 X-XSRF-TOKEN；CSRF_INVALID 重取 /auth/me 自動重試一次」
 * 以「修改密碼」（session 的非 GET 請求）當寫入請求；後端模型：只接受目前有效的 token，/auth/me 回應會把 cookie 換成有效值。
 */
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cookie, json, mockApi, noContent, problem, resetEnv, setCookie, start, teardown, text, user } from './helpers'

beforeEach(resetEnv)
afterEach(teardown)

const PW = '/api/v1/auth/password'

async function setup(opts: { alwaysInvalid?: boolean; status?: number; code?: string } = {}) {
  let valid = 'tok-valid-1'
  const api = mockApi({
    me: user(),
    routes: {
      'GET /api/v1/auth/me': () => {
        setCookie('XSRF-TOKEN', valid)
        return json(user())
      },
      [`POST ${PW}`]: (c) => {
        if (opts.status) return problem(opts.status, opts.code ?? 'FORBIDDEN')
        if (opts.alwaysInvalid || c.headers['x-xsrf-token'] !== valid) return problem(403, 'CSRF_INVALID')
        return noContent()
      },
    },
  })
  start('/')
  await screen.findAllByRole('navigation', { name: /主要導覽/ })
  const u = userEvent.setup()
  await u.click(screen.getAllByRole('link', { name: '修改密碼' })[0])
  await screen.findByRole('heading', { level: 1, name: '修改密碼' })
  return { api, u, rotate: (v: string) => (valid = v) }
}
async function submitForm(u: ReturnType<typeof userEvent.setup>) {
  await u.type(screen.getByLabelText('目前密碼'), 'old-password-123')
  await u.type(screen.getByLabelText('新密碼'), 'correct-horse-battery')
  await u.type(screen.getByLabelText('再輸入一次新密碼'), 'correct-horse-battery')
  await u.click(screen.getByRole('button', { name: '更新密碼' }))
}
const posts = (api: Awaited<ReturnType<typeof setup>>['api']) => api.count('POST', PW)

describe('X-XSRF-TOKEN 標頭', () => {
  it('寫入請求帶 X-XSRF-TOKEN，值 = 送出當下 cookie XSRF-TOKEN', async () => {
    const { api, u } = await setup()
    expect(cookie('XSRF-TOKEN')).toBe('tok-valid-1')
    await submitForm(u)
    await waitFor(() => expect(posts(api)).toHaveLength(1))
    expect(posts(api)[0].headers['x-xsrf-token']).toBe('tok-valid-1')
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('密碼已更新'))).toBe(true))
  })

  it('每次送出前重讀 cookie，不快取：頁面載入後 cookie 被換掉，標頭用新值（且不因此多打 /auth/me）', async () => {
    const { api, u, rotate } = await setup()
    rotate('tok-rotated-2')
    setCookie('XSRF-TOKEN', 'tok-rotated-2')
    const meBefore = api.meCount()
    await submitForm(u)
    await waitFor(() => expect(posts(api)).toHaveLength(1))
    expect(posts(api)[0].headers['x-xsrf-token']).toBe('tok-rotated-2')
    expect(api.meCount()).toBe(meBefore)
  })

  it('標頭值不寫進 localStorage／sessionStorage（不快取憑證）', async () => {
    const { u } = await setup()
    await submitForm(u)
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('密碼已更新'))).toBe(true))
    for (const st of [window.localStorage, window.sessionStorage]) {
      for (let i = 0; i < st.length; i++) expect(st.getItem(st.key(i)!) ?? '').not.toMatch(/tok-valid/)
    }
  })
})

describe('CSRF_INVALID：重取 /auth/me 後自動重試一次', () => {
  it('第一次 403 CSRF_INVALID → 呼叫一次 GET /auth/me → 用新 cookie 值重送一次 → 成功顯示「密碼已更新」，使用者看不到錯誤', async () => {
    const { api, u } = await setup()
    setCookie('XSRF-TOKEN', 'tok-stale-0') // 登入／登出等讓舊值失效，cookie 與後端不符
    const meBefore = api.meCount()
    await submitForm(u)
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('密碼已更新'))).toBe(true))
    expect(posts(api).map((c) => c.headers['x-xsrf-token'])).toEqual(['tok-stale-0', 'tok-valid-1'])
    expect(api.meCount()).toBe(meBefore + 1)
    const iFirst = api.calls.indexOf(posts(api)[0])
    const iMe = api.calls.findIndex((c, i) => i > iFirst && c.path === '/api/v1/auth/me')
    expect(iMe).toBeGreaterThan(iFirst)
    expect(iMe).toBeLessThan(api.calls.indexOf(posts(api)[1]))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(text()).not.toContain('操作未完成')
    expect(window.location.pathname).not.toBe('/login')
  })

  it('重試仍 CSRF_INVALID：不再重試（共 2 次 POST、1 次額外 /auth/me），顯示「操作未完成，請重新整理頁面」，不導向登入', async () => {
    const { api, u } = await setup({ alwaysInvalid: true })
    const meBefore = api.meCount()
    await submitForm(u)
    await waitFor(() => expect(text()).toContain('操作未完成，請重新整理頁面'))
    await new Promise((r) => setTimeout(r, 300)) // 確認沒有後續的重試
    expect(posts(api)).toHaveLength(2)
    expect(api.meCount()).toBe(meBefore + 1)
    expect(window.location.pathname).not.toBe('/login')
    expect(screen.getAllByRole('alert').some((a) => a.textContent?.includes('操作未完成，請重新整理頁面'))).toBe(true)
  })

  it('其他 403（FORBIDDEN）不重試、不重取 /auth/me', async () => {
    const { api, u } = await setup({ status: 403, code: 'FORBIDDEN' })
    const meBefore = api.meCount()
    await submitForm(u)
    await waitFor(() => expect(posts(api)).toHaveLength(1))
    await new Promise((r) => setTimeout(r, 300))
    expect(posts(api)).toHaveLength(1)
    expect(api.meCount()).toBe(meBefore)
  })

  it('重試時的請求內容與第一次相同（body 不變）', async () => {
    const { api, u } = await setup()
    setCookie('XSRF-TOKEN', 'tok-stale-0')
    await submitForm(u)
    await waitFor(() => expect(posts(api)).toHaveLength(2))
    expect(posts(api)[1].body).toEqual(posts(api)[0].body)
    expect(posts(api)[1].body).toEqual({ current_password: 'old-password-123', new_password: 'correct-horse-battery' })
  })
})
