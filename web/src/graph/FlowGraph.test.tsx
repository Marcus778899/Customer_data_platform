// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { FlowGraph } from './FlowGraph'
import { NODE_H, NODE_W, type GraphModel, type NodeState } from './model'

afterEach(cleanup)
beforeAll(() => {
  const g = globalThis as Record<string, unknown>
  if (!g.ResizeObserver) g.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
  if (!g.DOMMatrixReadOnly) g.DOMMatrixReadOnly = class { m22 = 1; constructor() {} }
})

const model = (state: NodeState): GraphModel => ({
  nodes: [
    {
      id: 'n1',
      x: 16,
      y: 16,
      data: { name: '一個相當長的節點名稱用來測試截斷', type: 'SendMessageNode', category: 'send', categoryLabel: '發送', state },
    },
  ],
  edges: [],
  width: NODE_W + 32,
  height: NODE_H + 32,
  textOrder: ['n1'],
})

describe.each([
  ['visited', '已經過'],
  ['current', '目前位置'],
  ['unvisited', '未經過'],
] as const)('節點 %s', (state, text) => {
  it('分類、名稱、類型、狀態都渲染，且狀態與分類同一列（不多佔一行）', () => {
    const { container } = render(<FlowGraph model={model(state)} label="g" />)
    const node = container.querySelector('.node')!
    expect(node.querySelector('.n-cat')!.textContent).toBe('發送')
    expect(node.querySelector('.n-name')!.textContent).toContain('節點名稱')
    expect(node.querySelector('.n-type')!.textContent).toBe('SendMessageNode')
    expect(node.textContent).toContain(text)
    // 直向只剩三列：標頭（分類＋狀態）、名稱、類型
    expect(node.children).toHaveLength(5) // 2 個 Handle + 3 列
    expect(node.querySelector('.n-head')!.textContent).toContain(text)
    expect(node.getAttribute('aria-label')).toContain(text)
  })
})

it('尺寸常數與排版', () => {
  expect(NODE_W).toBeGreaterThan(0)
  expect(NODE_H).toBeGreaterThan(0)
})
