// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { LoginPage } from './LoginPage'

const login = vi.fn()
vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), login: (...a: unknown[]) => login(...a), getMe: vi.fn() }))

afterEach(() => {
  cleanup()
  login.mockReset()
})

describe('登入頁 429 倒數（#145）', () => {
  it('改輸入其他 email 即解除倒數並可再送出；改回原 email 不會復活', async () => {
    login.mockRejectedValue(new ApiError(429, 'LOGIN_RATE_LIMITED', null, undefined, 300))
    const u = userEvent.setup()
    render(<LoginPage expired={false} onLoggedIn={() => undefined} />)
    await u.type(screen.getByLabelText('Email'), 'a@example.com')
    await u.type(screen.getByLabelText('密碼'), 'pw')
    await u.click(screen.getByRole('button', { name: '登入' }))
    expect(await screen.findByText(/登入失敗次數過多/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '請稍後再試' }).getAttribute('aria-disabled')).toBe('true')

    await u.type(screen.getByLabelText('Email'), 'x')
    expect(screen.queryByText(/登入失敗次數過多/)).toBeNull()
    const btn = screen.getByRole('button', { name: '登入' })
    expect(btn.getAttribute('aria-disabled')).toBe('false')

    login.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', null))
    await u.click(btn)
    expect(login).toHaveBeenLastCalledWith('a@example.comx', 'pw')
  })
})
