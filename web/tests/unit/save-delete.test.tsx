// @vitest-environment jsdom
/** journey-header-save-delete：離開攔截器、刪除失敗文案、一次性提示（Flash）。 */
import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../src/api/client'
import { FLASH_MS, FlashBar, dismissFlash, showFlash } from '../../src/components/Flash'
import { describeDeleteError } from '../../src/pages/review/DeleteDialog'
import { navigate, setNavigationBlocker } from '../../src/router'

afterEach(() => {
  setNavigationBlocker(null)
  dismissFlash()
  vi.useRealTimers()
})

describe('navigate 與離開攔截器', () => {
  it('攔截器回傳 true 就不導頁；回傳 false 或 replace 導覽照走；清除後恢復', () => {
    window.history.replaceState(null, '', '/start')
    const blocker = vi.fn(() => true)
    setNavigationBlocker(blocker)
    navigate('/other')
    expect(window.location.pathname).toBe('/start')
    expect(blocker).toHaveBeenCalledWith('/other')
    navigate('/login', { replace: true })
    expect(window.location.pathname).toBe('/login')
    expect(blocker).toHaveBeenCalledTimes(1)
    setNavigationBlocker(() => false)
    navigate('/other')
    expect(window.location.pathname).toBe('/other')
    setNavigationBlocker(null)
    navigate('/next')
    expect(window.location.pathname).toBe('/next')
  })
})

describe('describeDeleteError', () => {
  const e = (status: number, code: string | null) => new ApiError(status, code, 'R')
  it.each([
    [e(409, 'DRAFT_IN_REVIEW'), /撤回送審/, false, true],
    [e(409, 'JOURNEY_PUBLISHED'), /已發佈過/, false, true],
    [e(403, 'FORBIDDEN'), /沒有權限/, false, false],
    [e(503, 'UNAVAILABLE'), /稍後再試/, true, false],
    [new Error('x'), /稍後再試/, true, false],
  ])('%#', (err, re, retry, reload) => {
    const d = describeDeleteError(err)
    expect(d.text).toMatch(re)
    expect(d.retry).toBe(retry)
    expect(d.reload).toBe(reload)
  })
})

describe('FlashBar', () => {
  it('顯示提示、可手動關閉、約 6 秒自動消失', () => {
    vi.useFakeTimers()
    render(<FlashBar />)
    expect(screen.queryByRole('status')).toBeNull()
    act(() => showFlash('已刪除『甲』'))
    expect(screen.getByRole('status').textContent).toContain('已刪除『甲』')
    act(() => screen.getByRole('button', { name: '關閉提示' }).click())
    expect(screen.queryByRole('status')).toBeNull()
    act(() => showFlash('已刪除『乙』'))
    act(() => void vi.advanceTimersByTime(FLASH_MS - 1))
    expect(screen.queryByRole('status')).not.toBeNull()
    act(() => void vi.advanceTimersByTime(2))
    expect(screen.queryByRole('status')).toBeNull()
  })
})
