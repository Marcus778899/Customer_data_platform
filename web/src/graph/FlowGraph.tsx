// 唯讀旅程圖：@xyflow/react 負責節點與連線繪製，座標來自 model.ts（layout 或 dagre 由左到右）。
// 畫面固定 1:1、不縮放不拖曳；超出容器時容器自己左右捲動（窄螢幕提示改看文字版節點清單）。
import {
  BaseEdge,
  getSmoothStepPath,
  Handle,
  Position,
  ReactFlow,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import { memo, useMemo } from 'react'
import { NODE_H, NODE_W, type FlowNodeData, type GraphModel } from './model'

const STATE_TEXT = { visited: '已經過', current: '目前位置', unvisited: '未經過', plain: '' } as const

type StepViewData = FlowNodeData & { selected: boolean; onToggle: () => void }
type StepNode = Node<StepViewData, 'step'>

export const fmtCount = (n: number) => n.toLocaleString('en-US')

/** 軌跡的次數說明：目前位置「第 N 次」、已經過多次「第 N 次經過」 */
function visitText(d: FlowNodeData): string | null {
  if (d.state === 'current' && d.visit) return `第 ${d.visit} 次`
  if (d.state === 'visited' && d.passes) return `第 ${d.passes} 次經過`
  return null
}

const StepNodeView = memo(function StepNodeView({ data }: NodeProps<StepNode>) {
  const visit = visitText(data)
  const label = [
    data.name,
    `${data.categoryLabel}節點`,
    data.type,
    STATE_TEXT[data.state],
    visit,
    data.stat ? `人數 ${fmtCount(data.stat.entered)}，目前 ${fmtCount(data.stat.current)}` : null,
  ]
    .filter(Boolean)
    .join('，')
  return (
    <button
      type="button"
      className={`node cat-${data.category} ${data.state === 'plain' ? '' : data.state}${data.selected ? ' selected' : ''}`}
      aria-pressed={data.selected}
      aria-label={label}
      onClick={data.onToggle}
    >
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <span className="n-head">
        <span className="n-cat">{data.categoryLabel}</span>
        {data.state === 'visited' && <span className="n-state">✓ 已經過</span>}
        {data.state === 'current' && <span className="n-state">● 目前位置</span>}
        {data.state === 'unvisited' && <span className="sr">未經過</span>}
      </span>
      <span className="n-name">{data.name}</span>
      {data.stat ? (
        <span className="n-stat">
          <b>{fmtCount(data.stat.entered)}</b> 人數 <b>{fmtCount(data.stat.current)}</b> 目前
        </span>
      ) : (
        <span className="n-type">{visit ?? data.type}</span>
      )}
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </button>
  )
})

interface FlowEdgeData extends Record<string, unknown> {
  taken: boolean
  skipped: boolean
  back: boolean
}

function FlowEdgeView(props: EdgeProps<Edge<FlowEdgeData>>) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data } = props
  let path: string
  let label: { x: number; y: number } | null = null
  if (data?.back) {
    // 回邊：從節點下方繞回，點狀線
    const y = Math.max(sourceY, targetY) + NODE_H / 2 + 20
    label = { x: (sourceX + targetX) / 2, y: (sourceY + targetY) / 4 + (y * 3) / 4 + 14 }
    path = `M${sourceX} ${sourceY} C ${sourceX + 40} ${y}, ${targetX - 40} ${y}, ${targetX} ${targetY}`
  } else {
    ;[path] = getSmoothStepPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, borderRadius: 8 })
  }
  const cls = ['e', data?.taken && 'taken', data?.skipped && !data.taken && 'skipped', data?.back && 'back']
    .filter(Boolean)
    .join(' ')
  const markerId = `mk-${id}`
  return (
    <g aria-hidden="true">
      <defs>
        <marker
          id={markerId}
          className={data?.taken ? 'mk taken' : 'mk'}
          viewBox="0 0 10 10"
          refX="8"
          refY="5"
          markerWidth="10"
          markerHeight="10"
          markerUnits="userSpaceOnUse"
          orient="auto"
        >
          <path d="M0 0L10 5L0 10z" />
        </marker>
      </defs>
      <BaseEdge id={id} path={path} className={cls} markerEnd={`url(#${markerId})`} />
      {label && (
        <text className="e-label" x={label.x} y={label.y} textAnchor="middle">
          再等一輪
        </text>
      )}
    </g>
  )
}

const noop = () => undefined
const nodeTypes = { step: StepNodeView }
const edgeTypes = { flow: FlowEdgeView }

export function FlowGraph({
  model,
  label,
  selectedId = null,
  onSelect,
}: {
  model: GraphModel
  label: string
  selectedId?: string | null
  onSelect?: (id: string | null) => void
}) {
  const nodes = useMemo<StepNode[]>(
    () =>
      model.nodes.map((n) => ({
        id: n.id,
        type: 'step',
        position: { x: n.x, y: n.y },
        data: { ...n.data, selected: n.id === selectedId, onToggle: () => onSelect?.(n.id === selectedId ? null : n.id) },
        width: NODE_W,
        height: NODE_H,
        draggable: false,
        selectable: false,
        focusable: false,
        // 先給接點位置，連線不必等瀏覽器量測就能畫出來
        handles: [
          { type: 'target', position: Position.Left, x: 0, y: NODE_H / 2, width: 1, height: 1 },
          { type: 'source', position: Position.Right, x: NODE_W, y: NODE_H / 2, width: 1, height: 1 },
        ],
      })),
    [model, selectedId, onSelect],
  )
  const edges = useMemo<Edge<FlowEdgeData>[]>(
    () =>
      model.edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        type: 'flow',
        selectable: false,
        focusable: false,
        data: { taken: e.taken, skipped: e.skipped, back: e.back },
      })),
    [model],
  )
  return (
    <div className="graph" role="group" aria-label={label} tabIndex={0}>
      <div className="inner" style={{ width: model.width, height: model.height }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          defaultViewport={{ x: 0, y: 0, zoom: 1 }}
          minZoom={1}
          maxZoom={1}
          nodesDraggable={false}
          nodesConnectable={false}
          nodesFocusable={false}
          edgesFocusable={false}
          elementsSelectable={false}
          panOnDrag={false}
          panOnScroll={false}
          zoomOnScroll={false}
          zoomOnPinch={false}
          zoomOnDoubleClick={false}
          preventScrolling={false}
          // 有 click 處理才會讓節點接收指標事件（節點卡本身用 button 處理點擊）
          onNodeClick={noop}
          disableKeyboardA11y
        />
      </div>
    </div>
  )
}
