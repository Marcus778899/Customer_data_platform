// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const row = (o: object) => ({
  enrollment_id: 'e1', journey_name: '歡迎旅程', status: 'ACTIVE', current_node_id: 'w', current_node_type: 'WaitDelayNode',
  current_node_name: 'Wait PT2H', enrolled_at: '2026-10-18T02:00:00Z', terminated_at: null, ...o,
})
vi.mock('../api/client', () => ({
  getProfile: vi.fn(async () => ({
    profile_id: 'p1', identifiers: [], attributes: {}, masked_paths: [], created_at: '2026-10-01T00:00:00Z', updated_at: '2026-10-01T00:00:00Z',
  })),
  listProfileEnrollments: vi.fn(async () => ({
    items: [
      row({}),
      row({ enrollment_id: 'e2', status: 'COMPLETED', current_node_type: 'EndNode', current_node_name: '結束', terminated_at: '2026-10-19T02:00:00Z' }),
    ],
    next_cursor: null,
  })),
}))

import { CustomerPage } from './CustomerPage'

afterEach(cleanup)

describe('客戶頁旅程表（#144、#179）', () => {
  it('三欄；未結束寫「尚未結束」，已結束寫結束時間；步驟用白話', async () => {
    render(<CustomerPage id="p1" />)
    const t = await screen.findByRole('table', { name: /參加過/ })
    expect(within(t).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['旅程', '狀態', '進入與結束時間'])
    const [a, b] = within(t).getAllByRole('row').slice(1)
    expect(within(a).getAllByRole('cell')).toHaveLength(3)
    expect(a.textContent).toContain('現在在：等 2 小時')
    expect(a.textContent).toContain('尚未結束')
    expect(b.textContent).toContain('最後在：旅程結束')
    expect(b.textContent).toContain('結束')
    expect(b.textContent).not.toContain('尚未結束')
    expect(t.textContent).not.toContain('—')
  })
})
