// @vitest-environment jsdom
/**
 * app-shell-login 驗收測試：修改密碼（auth.md §3 /auth/password；UI：修改密碼、狀態表「修改密碼」列）
 * 對應驗收條件：「修改密碼的空／載入／錯誤／成功狀態與文案依 UI 規格」與無障礙（role、aria-invalid／describedby）。
 * 網址不假設：一律從帳號區「修改密碼」連結進入。
 */
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mockApi, noContent, problem, resetEnv, start, teardown, text, user } from './helpers'

beforeEach(resetEnv)
afterEach(teardown)

const OLD = 'old-password-123'
const NEW = 'correct-horse-battery'
const HINT = '12 到 72 字元，不要求大小寫或符號；中文字每字約佔 3 位元組。'

type Routes = NonNullable<Parameters<typeof mockApi>[0]>['routes']
async function openPassword(routes: Routes = {}) {
  const api = mockApi({ me: user(), routes })
  start('/')
  await screen.findAllByRole('navigation', { name: /主要導覽/ })
  const u = userEvent.setup()
  await u.click(screen.getAllByRole('link', { name: '修改密碼' })[0])
  await screen.findByRole('heading', { level: 1, name: '修改密碼' })
  return { api, u }
}
const cur = () => screen.getByLabelText('目前密碼') as HTMLInputElement
const nw = () => screen.getByLabelText('新密碼') as HTMLInputElement
const again = () => screen.getByLabelText('再輸入一次新密碼') as HTMLInputElement
const submit = () => screen.getByRole('button', { name: '更新密碼' })
const describedText = (el: HTMLElement) =>
  (el.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean).map((id) => document.getElementById(id)?.textContent ?? '').join(' ')
async function fillAll(u: ReturnType<typeof userEvent.setup>, c = OLD, n = NEW, a = NEW) {
  await u.type(cur(), c)
  await u.type(nw(), n)
  await u.type(again(), a)
}

describe('修改密碼：初始', () => {
  it('標題、登入帳號說明、三個密碼欄（autocomplete 正確）、新密碼提示、「更新密碼」「取消」', async () => {
    await openPassword()
    expect(text()).toContain('登入帳號：陳小美（amy.chen@example.com）')
    expect(cur().type).toBe('password')
    expect(cur().autocomplete).toBe('current-password')
    expect(nw().type).toBe('password')
    expect(nw().autocomplete).toBe('new-password')
    expect(again().autocomplete).toBe('new-password')
    expect(describedText(nw())).toContain(HINT)
    expect(screen.getByRole('form', { name: '修改密碼' })).toBeTruthy()
    expect(submit()).toBeTruthy()
    expect(screen.getByRole('link', { name: '取消' })).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByText(/密碼已更新/)).toBeNull()
  })

  it('外框仍在，且麵包屑為「旅程 › 修改密碼」', async () => {
    await openPassword()
    expect(screen.getAllByRole('navigation', { name: /主要導覽/ }).length).toBeGreaterThan(0)
    const crumbs = screen.getByRole('navigation', { name: '麵包屑' })
    expect(within(crumbs).getByRole('link', { name: '旅程' })).toBeTruthy()
    expect(crumbs.textContent).toContain('修改密碼')
  })

  it('「取消」回到旅程列表（/），不送任何寫入請求', async () => {
    const { api, u } = await openPassword()
    await u.click(screen.getByRole('link', { name: '取消' }))
    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: '修改密碼' })).toBeNull())
    expect(window.location.pathname).toBe('/')
    expect(api.calls.filter((c) => c.method !== 'GET')).toHaveLength(0)
  })
})

describe('修改密碼：成功（204）', () => {
  it('POST /auth/password 帶 { current_password, new_password }；role=status 顯示「密碼已更新。其他已登入的裝置不會被登出。」；欄位清空；仍留在本頁', async () => {
    const { api, u } = await openPassword()
    await fillAll(u)
    await u.click(submit())
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('密碼已更新。其他已登入的裝置不會被登出。'))).toBe(true))
    const posts = api.count('POST', '/api/v1/auth/password')
    expect(posts).toHaveLength(1)
    expect(posts[0].body).toEqual({ current_password: OLD, new_password: NEW })
    expect(posts[0].headers['content-type']).toContain('application/json')
    expect(cur().value).toBe('')
    expect(nw().value).toBe('')
    expect(again().value).toBe('')
    expect(window.location.pathname).not.toBe('/login')
    expect(screen.getByRole('heading', { level: 1, name: '修改密碼' })).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('成功後「確認欄」不會被送到後端（只有兩個欄位）', async () => {
    const { api, u } = await openPassword()
    await fillAll(u)
    await u.click(submit())
    await waitFor(() => expect(api.count('POST', '/api/v1/auth/password')).toHaveLength(1))
    expect(Object.keys(api.count('POST', '/api/v1/auth/password')[0].body as object).sort()).toEqual(['current_password', 'new_password'])
  })
})

describe('修改密碼：前端檢查與 400', () => {
  it('兩次輸入不一致：確認欄「兩次輸入的新密碼不一致。」aria-invalid，不送出請求', async () => {
    const { api, u } = await openPassword()
    await fillAll(u, OLD, NEW, 'correct-horse-batter')
    await u.click(submit())
    await waitFor(() => expect(again().getAttribute('aria-invalid')).toBe('true'))
    expect(describedText(again())).toContain('兩次輸入的新密碼不一致。')
    expect(api.count('POST', '/api/v1/auth/password')).toHaveLength(0)
    expect(nw().getAttribute('aria-invalid')).not.toBe('true')
  })

  it('400 VALIDATION_FAILED：新密碼欄「新密碼長度須為 12 到 72 字元（UTF-8 位元組數不超過 72）。」＋aria-invalid；提示文字仍在；輸入內容保留', async () => {
    const { u } = await openPassword({ 'POST /api/v1/auth/password': () => problem(400, 'VALIDATION_FAILED') })
    await fillAll(u, OLD, 'short', 'short')
    await u.click(submit())
    await waitFor(() => expect(nw().getAttribute('aria-invalid')).toBe('true'))
    const d = describedText(nw())
    expect(d).toContain('新密碼長度須為 12 到 72 字元（UTF-8 位元組數不超過 72）。')
    expect(d).toContain(HINT)
    expect(cur().value).toBe(OLD)
    expect(window.location.pathname).not.toBe('/login')
  })

  it('400 後修正再送，成功時錯誤標示消失', async () => {
    let n = 0
    const { u } = await openPassword({ 'POST /api/v1/auth/password': () => (++n === 1 ? problem(400, 'VALIDATION_FAILED') : noContent()) })
    await fillAll(u, OLD, 'short', 'short')
    await u.click(submit())
    await waitFor(() => expect(nw().getAttribute('aria-invalid')).toBe('true'))
    await u.clear(nw())
    await u.clear(again())
    await u.type(nw(), NEW)
    await u.type(again(), NEW)
    await u.click(submit())
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('密碼已更新'))).toBe(true))
    expect(nw().getAttribute('aria-invalid')).not.toBe('true')
  })
})

describe('修改密碼：401', () => {
  it('INVALID_CREDENTIALS（舊密碼錯）：「目前密碼不正確。」顯示在目前密碼欄、aria-invalid；不導向登入、其他欄保留', async () => {
    const { u } = await openPassword({ 'POST /api/v1/auth/password': () => problem(401, 'INVALID_CREDENTIALS') })
    await fillAll(u, 'wrong-old-password')
    await u.click(submit())
    await waitFor(() => expect(cur().getAttribute('aria-invalid')).toBe('true'))
    expect(describedText(cur())).toContain('目前密碼不正確。')
    expect(window.location.pathname).not.toBe('/login')
    expect(screen.getByRole('heading', { level: 1, name: '修改密碼' })).toBeTruthy()
    expect(nw().value).toBe(NEW)
    expect(text()).not.toContain('登入已逾時')
  })

  it('UNAUTHENTICATED（session 失效）：導向登入頁並顯示逾時提示', async () => {
    const { u } = await openPassword({ 'POST /api/v1/auth/password': () => problem(401, 'UNAUTHENTICATED') })
    await fillAll(u)
    await u.click(submit())
    await screen.findByLabelText('Email')
    expect(window.location.pathname).toBe('/login')
    expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('登入已逾時，請重新登入。'))).toBe(true)
  })
})

describe('修改密碼：載入中與其他錯誤', () => {
  it('送出中按鈕停用，連點只送一次；回應後恢復', async () => {
    let release: (r: Response) => void = () => {}
    const pending = new Promise<Response>((res) => (release = res))
    const { api, u } = await openPassword({ 'POST /api/v1/auth/password': () => pending })
    await fillAll(u)
    await u.click(submit())
    await waitFor(() => {
      const b = screen.getByRole('button', { name: /更新密碼|更新中/ })
      expect(b.getAttribute('aria-disabled') === 'true' || (b as HTMLButtonElement).disabled).toBe(true)
    })
    await u.click(screen.getByRole('button', { name: /更新密碼|更新中/ }))
    expect(api.count('POST', '/api/v1/auth/password')).toHaveLength(1)
    await act(async () => release(noContent()))
    await waitFor(() => expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('密碼已更新'))).toBe(true))
    const b = screen.getByRole('button', { name: '更新密碼' })
    expect((b as HTMLButtonElement).disabled).toBe(false)
  })

  it('5xx：role=alert 附 request_id（REQ-INTERNAL_ERROR），可再試，欄位保留', async () => {
    const { u } = await openPassword({ 'POST /api/v1/auth/password': () => problem(500, 'INTERNAL_ERROR') })
    await fillAll(u)
    await u.click(submit())
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('REQ-INTERNAL_ERROR')
    expect(nw().value).toBe(NEW)
    expect((submit() as HTMLButtonElement).disabled).toBe(false)
  })
})
