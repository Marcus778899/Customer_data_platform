// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('../api/client', () => ({ searchProfiles: vi.fn(async () => ({ items: [] })) }))

import { CustomerSearchPage } from './CustomerSearchPage'

afterEach(cleanup)

describe('客戶搜尋頁版面與文案（#148、#188）', () => {
  it('說明文字沒有內部決策編號，「完全比對」只出現一次', () => {
    render(<CustomerSearchPage loc={{ pathname: '/customers', search: '' }} />)
    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/D-\d{3}/)
    expect(text.match(/完全比對/g) ?? []).toHaveLength(1)
  })

  it('搜尋按鈕與輸入框在同一列（同一個 search-row），緊鄰輸入框', () => {
    render(<CustomerSearchPage loc={{ pathname: '/customers', search: '' }} />)
    const row = screen.getByLabelText('搜尋客戶').closest('.search-row')
    expect(row).not.toBeNull()
    expect(row!.querySelector('button[type="submit"]')?.previousElementSibling).toBe(screen.getByLabelText('搜尋客戶'))
  })

  it('查無結果後送出空白：只剩「請輸入要搜尋的內容」，舊的「找不到」消失', async () => {
    render(<CustomerSearchPage loc={{ pathname: '/customers', search: '?q=zzz' }} />)
    await screen.findByText('找不到符合的客戶')
    const box = screen.getByLabelText('搜尋客戶')
    await userEvent.clear(box)
    await userEvent.click(screen.getByRole('button', { name: '搜尋' }))
    expect(await screen.findByText('請輸入要搜尋的內容。')).toBeTruthy()
    expect(screen.queryByText('找不到符合的客戶')).toBeNull()
  })
})
