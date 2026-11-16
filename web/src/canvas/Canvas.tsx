// 編輯畫布：@xyflow/react 負責繪製與縮放；節點是 <button aria-pressed>，邊與裝飾 aria-hidden。
// 連線在右側面板的「下一步」選單設定；節點可拖曳，座標存進 layout。
import {
  BaseEdge,
  getSmoothStepPath,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import { memo, useMemo, useState } from 'react'
import {
  CARD_H,
  CARD_W,
  categoryOf,
  displayName,
  fillLayout,
  outputSpecs,
  summaryOf,
  targetsOf,
  type Def,
  type Layout,
} from './model'

interface CardData extends Record<string, unknown> {
  name: string
  categoryLabel: string
  category: string
  summary: string
  selected: boolean
  hasError: boolean
  onSelect: (id: string) => void
  id: string
}
type CardNode = Node<CardData, 'card'>

const CardView = memo(function CardView({ data }: NodeProps<CardNode>) {
  return (
    <button
      type="button"
      className={`cnode cat-${data.category}${data.selected ? ' selected' : ''}${data.hasError ? ' has-error' : ''}`}
      aria-pressed={data.selected}
      onClick={() => data.onSelect(data.id)}
    >
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <span className="n-head">
        <span className="n-cat">{data.categoryLabel}</span>
        {data.hasError && <span className="n-err">有錯誤</span>}
      </span>
      <span className="n-name">{data.name}</span>
      <span className="n-sum">{data.summary}</span>
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </button>
  )
})

interface LinkData extends Record<string, unknown> {
  back: boolean
  label: string
}

function LinkView(props: EdgeProps<Edge<LinkData>>) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data } = props
  let path: string
  if (data?.back) {
    const y = Math.max(sourceY, targetY) + CARD_H / 2 + 24
    path = `M${sourceX} ${sourceY} C ${sourceX + 60} ${y}, ${targetX - 60} ${y}, ${targetX} ${targetY}`
  } else {
    ;[path] = getSmoothStepPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, borderRadius: 8 })
  }
  const marker = `mk-${id}`
  return (
    <g aria-hidden="true">
      <defs>
        <marker id={marker} className="mk" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="10" markerHeight="10" markerUnits="userSpaceOnUse" orient="auto">
          <path d="M0 0L10 5L0 10z" />
        </marker>
      </defs>
      <BaseEdge
        id={id}
        path={path}
        className={`e${data?.back ? ' back' : ''}`}
        markerEnd={`url(#${marker})`}
      />
      {data?.label && (
        <text className="e-label" x={(sourceX + targetX) / 2} y={(sourceY + targetY) / 2 - 6} textAnchor="middle">
          {data.label}
        </text>
      )}
    </g>
  )
}

const nodeTypes = { card: CardView }
const edgeTypes = { link: LinkView }

function Zoom() {
  const rf = useReactFlow()
  const zoom = useStore((s) => s.transform[2])
  return (
    <div className="zoombar" role="group" aria-label="縮放">
      <button type="button" className="btn secondary sm" aria-label="縮小" onClick={() => void rf.zoomOut()}>
        −
      </button>
      <span className="muted" aria-live="off">
        {Math.round(zoom * 100)}%
      </span>
      <button type="button" className="btn secondary sm" aria-label="放大" onClick={() => void rf.zoomIn()}>
        +
      </button>
      <button
        type="button"
        className="btn secondary sm"
        aria-label="符合視窗大小"
        onClick={() => {
          try {
            void rf.fitView({ padding: 0.1, maxZoom: 1 })
          } catch {
            // 容器尚無尺寸時忽略
          }
        }}
      >
        ⤢
      </button>
    </div>
  )
}

export function Canvas({
  def,
  layout,
  selectedId,
  errorIds,
  onSelect,
  onMove,
}: {
  def: Def
  layout: Layout | null
  selectedId: string | null
  errorIds: ReadonlySet<string>
  onSelect: (id: string) => void
  onMove: (id: string, pos: { x: number; y: number }) => void
}) {
  const [drag, setDrag] = useState<Record<string, { x: number; y: number }>>({})
  const full = useMemo(() => fillLayout(def, layout), [def, layout])

  const nodes = useMemo<CardNode[]>(
    () =>
      def.nodes.map((n) => {
        const meta = categoryOf(n.type)
        return {
          id: n.id,
          type: 'card',
          position: drag[n.id] ?? full.nodes[n.id] ?? { x: 0, y: 0 },
          width: CARD_W,
          height: CARD_H,
          selectable: false,
          focusable: false,
          dragHandle: '.n-head',
          handles: [
            { type: 'target', position: Position.Left, x: 0, y: CARD_H / 2, width: 1, height: 1 },
            { type: 'source', position: Position.Right, x: CARD_W, y: CARD_H / 2, width: 1, height: 1 },
          ],
          data: {
            id: n.id,
            name: displayName(n),
            category: meta.category,
            categoryLabel: meta.label,
            summary: summaryOf(n),
            selected: n.id === selectedId,
            hasError: errorIds.has(n.id),
            onSelect,
          },
        }
      }),
    [def, full, drag, selectedId, errorIds, onSelect],
  )

  const edges = useMemo<Edge<LinkData>[]>(() => {
    const ids = new Set(def.nodes.map((n) => n.id))
    const out: Edge<LinkData>[] = []
    for (const n of def.nodes) {
      const specs = outputSpecs(n)
      const multi = specs.length > 1
      for (const t of targetsOf(n)) {
        if (!ids.has(t.target)) continue
        const a = drag[n.id] ?? full.nodes[n.id]
        const b = drag[t.target] ?? full.nodes[t.target]
        const label = multi ? (specs.find((s) => s.port === t.port)?.label.split('（')[0] ?? '') : ''
        out.push({
          id: `${n.id}:${t.port}`,
          source: n.id,
          target: t.target,
          type: 'link',
          selectable: false,
          focusable: false,
          data: { back: !!a && !!b && b.x <= a.x, label },
        })
      }
    }
    return out
  }, [def, full, drag])

  return (
    <ReactFlowProvider>
      <div className="canvas-wrap">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          defaultViewport={{ x: 24, y: 24, zoom: 0.8 }}
          minZoom={0.3}
          maxZoom={1.5}
          nodesConnectable={false}
          nodesFocusable={false}
          edgesFocusable={false}
          elementsSelectable={false}
          zoomOnDoubleClick={false}
          preventScrolling={false}
          disableKeyboardA11y
          onNodeDrag={(_, node) => setDrag((d) => ({ ...d, [node.id]: node.position }))}
          onNodeDragStop={(_, node) => {
            onMove(node.id, { x: Math.round(node.position.x), y: Math.round(node.position.y) })
            setDrag({})
          }}
        >
          <MiniMap aria-hidden="true" pannable zoomable nodeStrokeWidth={2} />
        </ReactFlow>
        <Zoom />
      </div>
    </ReactFlowProvider>
  )
}
