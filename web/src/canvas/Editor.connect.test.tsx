// @vitest-environment jsdom
import { fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockLayout, restoreLayout } from '../../tests/acceptance/canvas-fit-fix/fixtures'
import { Icon } from '../components/CanvasIcons'
import { NodeLibrary } from './NodeLibrary'
import { NODE_MIME } from './model'
import { render, screen } from '@testing-library/react'
import {
  JID, cleanup, draftBody, editorBackend, json, libraryItem, library, nodeButtons, openEditor, resetEnv, smallDef, teardown, userEvent, waitFor, waitForPut,
} from '../../tests/acceptance/journey-canvas-ui/fixtures'

beforeEach(() => {
  resetEnv()
  mockLayout()
  localStorage.clear()
})
afterEach(() => {
  cleanup()
  teardown()
  restoreLayout()
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('NodeLibrary 拖曳', () => {
  it('dragstart 帶出節點類型；送審鎖定時不能拖、不能按', () => {
    const onAdd = vi.fn()
    const { rerender } = render(<NodeLibrary onAdd={onAdd} locked={false} />)
    const data = new Map<string, string>()
    const dt = { setData: (k: string, v: string) => data.set(k, v), effectAllowed: '' }
    fireEvent.dragStart(libraryItem(screen.getByRole('complementary', { name: '節點庫' }), '訊息'), { dataTransfer: dt })
    expect(data.get(NODE_MIME)).toBe('SendMessageNode')
    rerender(<NodeLibrary onAdd={onAdd} locked />)
    const item = libraryItem(screen.getByRole('complementary', { name: '節點庫' }), '訊息')
    const ev = fireEvent.dragStart(item, { dataTransfer: dt })
    expect(ev).toBe(false)
    fireEvent.click(item)
    expect(onAdd).not.toHaveBeenCalled()
  })
})

describe('圖示', () => {
  it('沒有對應名稱時畫預設圓點', () => {
    const { container } = render(<Icon name="unknown" />)
    expect(container.querySelector('svg circle')).toBeTruthy()
  })
})

describe('編輯畫布：拖入與首次提示', () => {
  it('把節點庫項目放到畫布上：在放開處新增節點並選取', async () => {
    const ed = editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    await waitFor(() => expect(nodeButtons().length).toBe(6))
    const wrap = document.querySelector('.canvas-wrap')!
    const dt = { types: [NODE_MIME], getData: () => 'EndNode' }
    fireEvent.dragOver(wrap, { dataTransfer: dt })
    expect(screen.getByText('放開就新增在這裡')).toBeTruthy()
    fireEvent.drop(wrap, { dataTransfer: dt, clientX: 300, clientY: 300 })
    await waitForPut((b) => b.definition.nodes.length === 7, ed)
    expect(screen.queryByText('放開就新增在這裡')).toBeNull()
    // 非節點庫的拖放不處理
    fireEvent.drop(wrap, { dataTransfer: { types: [], getData: () => '' } })
    fireEvent.dragLeave(wrap)
    expect(nodeButtons().length).toBe(7)
  })

  it('localStorage 不能用時照樣顯示首次提示、按「知道了」不報錯；出口圓點按空白鍵與點擊', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    const dot = await waitFor(() => {
      const d = document.querySelector<HTMLButtonElement>('button.out-btn')
      expect(d).toBeTruthy()
      return d!
    })
    await userEvent.click(dot)
    dot.focus()
    await userEvent.keyboard(' ')
    await waitFor(() => expect(screen.getByLabelText('名稱')).toBeTruthy())
    await userEvent.click(await screen.findByRole('button', { name: '知道了' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: '知道了' })).toBeNull())
    expect(library()).toBeTruthy()
  })
})

describe('送審鎖定', () => {
  it('點線不選取、選取節點後按 Delete 不刪、live region 不報讀', async () => {
    const pending = { review_id: 'r1', journey_id: 'j', action: 'PUBLISH', status: 'PENDING', revision: 1, base_version: null, note: '', submitted_by: { user_id: 'x', display_name: 'x' }, submitted_at: '2026-11-07T02:00:00Z', decided_by: null, decided_at: null, reason: null }
    const ed = editorBackend({
      def: smallDef(),
      revision: 1,
      routes: {
        [`GET /api/v1/journeys/${JID}/draft`]: () => json(draftBody({ revision: 1, latest_review: pending })),
      },
    })
    await openEditor()
    await waitFor(() => expect(nodeButtons().length).toBe(6))
    const path = document.querySelector('[data-testid="rf__edge-wait_1:next"] path')!
    await userEvent.click(path)
    expect(screen.queryByRole('button', { name: '移除連線' })).toBeNull()
    await userEvent.click(nodeButtons().find((b) => (b.textContent ?? '').includes('VIP 專屬關心'))!)
    await userEvent.keyboard('{Delete}')
    expect(nodeButtons().length).toBe(6)
    expect(screen.getAllByRole('status').some((s) => /已刪除|已移除|已連接/.test(s.textContent ?? ''))).toBe(false)
    expect(ed.puts().length).toBe(0)
  })
})
