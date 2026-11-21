// @vitest-environment jsdom
// RF 換成只記下 props 的替身：jsdom 無法真的拖線，這裡直接呼叫畫布傳給 RF 的連線處理函式。
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const rf: { props: any } = { props: null }
vi.mock('@xyflow/react', async (orig) => {
  const actual = await orig<typeof import('@xyflow/react')>()
  return {
    ...actual,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ReactFlow: (props: any) => {
      rf.props = props
      return <div data-testid="rf" />
    },
  }
})

import { Canvas } from './Canvas'
import type { Def } from './model'

const def: Def = {
  name: 't', description: null, settings: {}, entry_node: 'start',
  nodes: [
    { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'cond' } },
    { id: 'cond', type: 'ConditionNode', name: '條件', config: {}, outputs: { yes: 'end' } },
    { id: 'end', type: 'EndNode', name: '結束', config: {}, outputs: {} },
  ],
}
const fns = () => ({
  onSelect: vi.fn(), onSelectEdge: vi.fn(), onMove: vi.fn(), onConnect: vi.fn(), onRemoveLink: vi.fn(), onPortKey: vi.fn(), onAddAt: vi.fn(),
})
const show = (over: Record<string, unknown> = {}) => {
  const f = fns()
  render(<Canvas def={def} layout={null} selectedId={null} selectedEdge={null} errorIds={new Set()} {...f} {...over} />)
  return f
}
const endState = (over: Record<string, unknown> = {}) => ({
  isValid: false, toNode: null, fromNode: { id: 'cond' }, fromHandle: { id: 'no' }, ...over,
})

beforeEach(() => localStorage.clear())
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('Canvas 連線處理', () => {
  it('isValidConnection：不可連自己與進入條件；onConnect 轉給父層並讓首次提示消失', () => {
    const f = show()
    const { isValidConnection, onConnect } = rf.props
    expect(isValidConnection({ source: 'cond', target: 'cond' })).toBe(false)
    expect(isValidConnection({ source: 'cond', target: 'start' })).toBe(false)
    expect(isValidConnection({ source: 'cond', target: 'end' })).toBe(true)
    expect(screen.getByRole('button', { name: '知道了' })).toBeTruthy()
    act(() => onConnect({ source: 'cond', sourceHandle: 'no', target: 'end' }))
    expect(f.onConnect).toHaveBeenCalledWith('cond', 'no', 'end')
    expect(screen.queryByRole('button', { name: '知道了' })).toBeNull()
    act(() => onConnect({ source: 'cond', sourceHandle: null, target: 'end' }))
    act(() => onConnect({ source: 'cond', sourceHandle: 'no', target: 'cond' }))
    expect(f.onConnect).toHaveBeenCalledTimes(1)
  })

  it('放開在空白處：跳「接上新節點」選單，選一項帶著來源出口新增；取消與 Esc 關閉', () => {
    const f = show()
    act(() => rf.props.onConnectStart({ clientX: 10, clientY: 10 }))
    act(() => rf.props.onConnectEnd({ clientX: 400, clientY: 300 }, endState()))
    expect(screen.getByRole('group', { name: '接上新節點' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '訊息' }))
    expect(f.onAddAt).toHaveBeenCalledWith('SendMessageNode', expect.objectContaining({ x: expect.any(Number) }), { id: 'cond', port: 'no' })
    expect(screen.queryByRole('group', { name: '接上新節點' })).toBeNull()

    act(() => rf.props.onConnectEnd({ changedTouches: [{ clientX: 400, clientY: 300 }] }, endState()))
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('group', { name: '接上新節點' })).toBeNull()

    act(() => rf.props.onConnectEnd({ clientX: 400, clientY: 300 }, endState()))
    fireEvent.keyDown(document, { key: 'Enter' })
    expect(screen.getByRole('group', { name: '接上新節點' })).toBeTruthy()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('group', { name: '接上新節點' })).toBeNull()
  })

  it('不開選單：點一下圓點（沒拖）、放在節點上、已連成功、送審鎖定、沒有觸點資料', () => {
    show()
    act(() => rf.props.onConnectStart({ clientX: 400, clientY: 300 }))
    act(() => rf.props.onConnectEnd({ clientX: 401, clientY: 300 }, endState()))
    act(() => rf.props.onConnectEnd({ clientX: 500, clientY: 300 }, endState({ toNode: { id: 'end' } })))
    act(() => rf.props.onConnectEnd({ clientX: 500, clientY: 300 }, endState({ isValid: true })))
    act(() => rf.props.onConnectEnd({ clientX: 500, clientY: 300 }, endState({ fromNode: null })))
    act(() => rf.props.onConnectEnd({ changedTouches: [] }, endState()))
    expect(screen.queryByRole('group', { name: '接上新節點' })).toBeNull()
  })

  it('鎖定時放開在空白處不開選單；來源節點已不存在也不出錯', () => {
    show({ locked: true })
    act(() => rf.props.onConnectEnd({ clientX: 400, clientY: 300 }, endState()))
    expect(screen.queryByRole('group', { name: '接上新節點' })).toBeNull()
  })

  it('來源節點不在 def 裡：仍能開選單', () => {
    show()
    act(() => rf.props.onConnectEnd({ clientX: 400, clientY: 300 }, endState({ fromNode: { id: 'ghost' } })))
    expect(screen.getByRole('group', { name: '接上新節點' })).toBeTruthy()
  })

  it('點線選取、點空白取消選取並關選單；拖曳節點回報座標', () => {
    const f = show()
    act(() => rf.props.onEdgeClick({}, { id: 'cond:yes' }))
    expect(f.onSelectEdge).toHaveBeenCalledWith('cond:yes')
    act(() => rf.props.onConnectEnd({ clientX: 400, clientY: 300 }, endState()))
    act(() => rf.props.onPaneClick())
    expect(f.onSelectEdge).toHaveBeenLastCalledWith(null)
    expect(screen.queryByRole('group', { name: '接上新節點' })).toBeNull()
    act(() => rf.props.onNodeDrag({}, { id: 'end', position: { x: 5.4, y: 6.6 } }))
    act(() => rf.props.onNodeDragStop({}, { id: 'end', position: { x: 5.4, y: 6.6 } }))
    expect(f.onMove).toHaveBeenCalledWith('end', { x: 5, y: 7 })
  })

  it('放到畫布：以放開處新增節點', () => {
    const f = show()
    const wrap = document.querySelector('.canvas-wrap')!
    fireEvent.drop(wrap, { dataTransfer: { types: ['application/x-cdp-node-type'], getData: () => 'EndNode' }, clientX: 400, clientY: 300 })
    expect(f.onAddAt).toHaveBeenCalledWith('EndNode', expect.any(Object))
  })
  it('鎖定時拖入不新增', () => {
    const f = show({ locked: true })
    const wrap = document.querySelector('.canvas-wrap')!
    fireEvent.dragOver(wrap, { dataTransfer: { types: ['application/x-cdp-node-type'] } })
    fireEvent.drop(wrap, { dataTransfer: { types: ['application/x-cdp-node-type'], getData: () => 'EndNode' } })
    expect(f.onAddAt).not.toHaveBeenCalled()
  })
})
