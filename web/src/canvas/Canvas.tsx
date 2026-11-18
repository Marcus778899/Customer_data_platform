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
import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  CARD_H,
  CARD_W,
  categoryOf,
  displayName,
  fillLayout,
  outputSpecs,
  summaryOf,
  targetsOf,
  topologyKey,
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

const MINI_FILL = () => 'var(--color-border-strong)'
const MINI_STROKE = () => 'var(--color-text-muted)'
const nodeTypes = { card: CardView }
const edgeTypes = { link: LinkView }

/** 小地圖尺寸與邊距（右下角）；符合視窗時要讓出這塊 */
const MINI = { w: 160, h: 100, edge: 15 } as const
const PAD = 24
const FIT_MAX_ZOOM = 0.8
const FIT_MIN_ZOOM = 0.1
/** 窄畫布（手機）隱藏小地圖，只讓出縮放列 */
const MINI_MIN_WIDTH = 640
const ZOOMBAR_BAND = 64

type Fit = { rf: ReturnType<typeof useReactFlow>; box: HTMLElement | null; positions: () => Record<string, { x: number; y: number }> }

/**
 * 符合視窗大小：自己用節點座標與容器實際大小算 viewport，不靠 fitView。
 * 根因：xyflow 的 fitView 用內部量到的 pane 尺寸與節點量測值，編輯中節點剛新增、排版剛重排時
 * 量測還沒更新，fitView 用舊尺寸排隊執行，結果縮放與位置沒變（F-19）；內建 fitView 也不會避開小地圖與縮放列（F-21）。
 */
function fit({ rf, box, positions }: Fit) {
  const w = box?.offsetWidth ?? 0
  const h = box?.offsetHeight ?? 0
  const pos = Object.values(positions())
  if (!w || !h || pos.length === 0) return
  const minX = Math.min(...pos.map((p) => p.x))
  const maxX = Math.max(...pos.map((p) => p.x + CARD_W))
  const minY = Math.min(...pos.map((p) => p.y))
  const maxY = Math.max(...pos.map((p) => p.y + CARD_H))
  const bottom = w >= MINI_MIN_WIDTH ? MINI.edge + MINI.h + PAD / 3 : ZOOMBAR_BAND
  const availW = Math.max(1, w - PAD * 2)
  const availH = Math.max(1, h - PAD - bottom)
  const bw = maxX - minX
  const bh = maxY - minY
  const zoom = Math.max(FIT_MIN_ZOOM, Math.min(FIT_MAX_ZOOM, availW / bw, availH / bh))
  const x = PAD + (availW - bw * zoom) / 2 - minX * zoom
  const y = PAD + (availH - bh * zoom) / 2 - minY * zoom
  void Promise.resolve(rf.setViewport({ x, y, zoom })).catch(() => undefined)
}

/** 加節點、接線、畫布大小改變（開關右側面板、視窗縮放）後自動讓整張圖進入畫面；拖曳不觸發 */
function FitOnChange({ signature, wrap, positions }: { signature: string; wrap: React.RefObject<HTMLDivElement | null>; positions: () => Record<string, { x: number; y: number }> }) {
  const rf = useReactFlow()
  useEffect(() => {
    const box = wrap.current?.querySelector<HTMLElement>('.react-flow') ?? null
    const run = () => fit({ rf, box, positions })
    let raf = window.requestAnimationFrame(run)
    let ro: ResizeObserver | undefined
    if (box && typeof ResizeObserver !== 'undefined') {
      ro = new ResizeObserver(() => {
        window.cancelAnimationFrame(raf)
        raf = window.requestAnimationFrame(run)
      })
      ro.observe(box)
    }
    return () => {
      window.cancelAnimationFrame(raf)
      ro?.disconnect()
    }
  }, [signature, rf, wrap, positions])
  return null
}

function ZoomFit({ wrap, positions }: { wrap: React.RefObject<HTMLDivElement | null>; positions: () => Record<string, { x: number; y: number }> }) {
  const rf = useReactFlow()
  return <Zoom onFit={() => fit({ rf, box: wrap.current?.querySelector<HTMLElement>('.react-flow') ?? null, positions })} />
}

function Zoom({ onFit }: { onFit: () => void }) {
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
        onClick={onFit}
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
  const signature = useMemo(() => topologyKey(def), [def])
  const wrap = useRef<HTMLDivElement>(null)
  const fullRef = useRef(full)
  useLayoutEffect(() => {
    fullRef.current = full
  }, [full])
  const positions = useCallback(() => fullRef.current.nodes, [])

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
      <div className="canvas-wrap" ref={wrap}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          defaultViewport={{ x: PAD, y: PAD, zoom: FIT_MAX_ZOOM }}
          minZoom={0.1}
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
          <MiniMap aria-hidden="true" style={{ width: MINI.w, height: MINI.h, margin: MINI.edge }} pannable zoomable nodeStrokeWidth={2} nodeColor={MINI_FILL} nodeStrokeColor={MINI_STROKE} />
        </ReactFlow>
        <ZoomFit wrap={wrap} positions={positions} />
        <FitOnChange signature={signature} wrap={wrap} positions={positions} />
      </div>
    </ReactFlowProvider>
  )
}
