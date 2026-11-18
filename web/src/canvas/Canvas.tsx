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

/** 小地圖尺寸與邊距（右下角） */
const MINI = { w: 160, h: 100, edge: 15 } as const
const PAD = 24
/** 自動縮放（載入、新增節點、接線）：下限 70%（窄畫布固定 85%），上限 80%；只有「看全圖」可以更小 */
const AUTO_MIN = 0.7
const AUTO_MAX = 0.8
const NARROW_ZOOM = 0.85
const MIN_ZOOM = 0.25
const MAX_ZOOM = 1.5
/** 縮放低於這個值要警告（文字偏小） */
const READABLE = 0.6
/** 窄畫布（手機）不顯示縮放列與小地圖 */
const NARROW_W = 640
/** 面板排到圖下方的界線（同 app.css） */
const STACK_W = 1100
const ZOOMBAR_BAND = 64
const PANEL_FALLBACK_W = 372
const REVEAL_GAP = 16

type Positions = () => Record<string, { x: number; y: number }>
type Size = { w: number; h: number }
type Rf = ReturnType<typeof useReactFlow>
type Wrap = React.RefObject<HTMLDivElement | null>

/** 量畫布容器的實際大小（容器改變時更新） */
function useBoxSize(wrap: Wrap): Size {
  const [size, setSize] = useState<Size>({ w: 0, h: 0 })
  useEffect(() => {
    const box = wrap.current?.querySelector<HTMLElement>('.react-flow') ?? null
    if (!box) return
    const read = () => setSize((s) => (s.w === box.offsetWidth && s.h === box.offsetHeight ? s : { w: box.offsetWidth, h: box.offsetHeight }))
    read()
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(read)
    ro.observe(box)
    return () => ro.disconnect()
  }, [wrap])
  return size
}

function bounds(positions: Positions) {
  const pos = Object.values(positions())
  if (pos.length === 0) return null
  const minX = Math.min(...pos.map((p) => p.x))
  const maxX = Math.max(...pos.map((p) => p.x + CARD_W))
  const minY = Math.min(...pos.map((p) => p.y))
  const maxY = Math.max(...pos.map((p) => p.y + CARD_H))
  return { minX, minY, bw: maxX - minX, bh: maxY - minY }
}

const boxOf = (wrap: Wrap) => wrap.current?.querySelector<HTMLElement>('.react-flow') ?? null
const apply = (rf: Rf, x: number, y: number, zoom: number) => void Promise.resolve(rf.setViewport({ x, y, zoom })).catch(() => undefined)

/**
 * 自動縮放（F-31）：可讀優先。縮放不低於下限；圖放得進畫布就置中，放不進就從最左（開始）節點起靠左、頂端對齊，左右捲動或拖曳。
 * 自己用節點座標與容器大小算 viewport，不靠 fitView（fitView 在節點剛新增時用舊量測值，F-19）。
 */
function place(rf: Rf, wrap: Wrap, positions: Positions) {
  const box = boxOf(wrap)
  const w = box?.offsetWidth ?? 0
  const h = box?.offsetHeight ?? 0
  const b = bounds(positions)
  if (!w || !h || !b) return
  const availW = Math.max(1, w - PAD * 2)
  const availH = Math.max(1, h - PAD - ZOOMBAR_BAND)
  const zoom = w <= NARROW_W ? NARROW_ZOOM : Math.max(AUTO_MIN, Math.min(AUTO_MAX, availW / b.bw, availH / b.bh))
  const x = b.bw * zoom <= availW ? PAD + (availW - b.bw * zoom) / 2 - b.minX * zoom : PAD - b.minX * zoom
  const y = b.bh * zoom <= availH ? PAD + (availH - b.bh * zoom) / 2 - b.minY * zoom : PAD - b.minY * zoom
  apply(rf, x, y, zoom)
}

/** 看全圖：整張圖進畫布，水平與垂直置中；上下各讓出縮放列的高度，下限 25%、上限 80% */
function fitAll(rf: Rf, wrap: Wrap, positions: Positions) {
  const box = boxOf(wrap)
  const w = box?.offsetWidth ?? 0
  const h = box?.offsetHeight ?? 0
  const b = bounds(positions)
  if (!w || !h || !b) return
  const availW = Math.max(1, w - PAD * 2)
  const availH = Math.max(1, h - ZOOMBAR_BAND * 2)
  const zoom = Math.max(MIN_ZOOM, Math.min(AUTO_MAX, availW / b.bw, availH / b.bh))
  apply(rf, PAD + (availW - b.bw * zoom) / 2 - b.minX * zoom, ZOOMBAR_BAND + (availH - b.bh * zoom) / 2 - b.minY * zoom, zoom)
}

/** 符合寬度：圖的寬度剛好填滿，上限 100%（面板收合後想放大一鍵） */
function fitWidth(rf: Rf, wrap: Wrap, positions: Positions) {
  const box = boxOf(wrap)
  const w = box?.offsetWidth ?? 0
  const b = bounds(positions)
  if (!w || !b) return
  const zoom = Math.max(MIN_ZOOM, Math.min(1, (w - PAD * 2) / b.bw))
  apply(rf, PAD - b.minX * zoom, rf.getViewport().y, zoom)
}

/** 加節點、接線、畫布大小改變後自動縮放並置位；拖曳不觸發，開關面板不改畫布大小所以也不觸發 */
function FitOnChange({ signature, wrap, positions }: { signature: string; wrap: Wrap; positions: Positions }) {
  const rf = useReactFlow()
  useEffect(() => {
    const box = boxOf(wrap)
    const run = () => place(rf, wrap, positions)
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

/**
 * 選取節點：只平移、不改縮放。桌機（面板浮在畫布右側）只在節點被面板蓋住時往左平移，露出節點、左側留 16px；
 * 窄螢幕（面板在圖下方）把節點捲到水平置中、上方留 8px。被平移時回報，畫布顯示提示。
 */
function RevealSelected({
  selectedId,
  panelOpen,
  wrap,
  positions,
  onMoved,
}: {
  selectedId: string | null
  panelOpen: boolean
  wrap: Wrap
  positions: Positions
  onMoved: (id: string) => void
}) {
  const rf = useReactFlow()
  useEffect(() => {
    if (!selectedId || !panelOpen) return
    const run = () => {
      const box = boxOf(wrap)
      const w = box?.offsetWidth ?? 0
      const h = box?.offsetHeight ?? 0
      const p = positions()[selectedId]
      if (!w || !h || !p) return
      const vp = rf.getViewport()
      const left = vp.x + p.x * vp.zoom
      const right = left + CARD_W * vp.zoom
      const top = vp.y + p.y * vp.zoom
      if (w <= STACK_W) {
        const nx = w / 2 - (p.x + CARD_W / 2) * vp.zoom
        const ny = top < 8 || top + CARD_H * vp.zoom > h ? 8 - p.y * vp.zoom : vp.y
        if (Math.abs(nx - vp.x) > 0.5 || Math.abs(ny - vp.y) > 0.5) apply(rf, nx, ny, vp.zoom)
        return
      }
      const panelW = wrap.current?.parentElement?.querySelector<HTMLElement>('.edside')?.offsetWidth || PANEL_FALLBACK_W
      const limit = w - panelW - REVEAL_GAP
      if (right <= limit) return
      const shift = Math.min(right - limit, left - REVEAL_GAP)
      if (shift <= 0.5) return
      apply(rf, vp.x - shift, vp.y, vp.zoom)
      onMoved(selectedId)
    }
    const raf = window.requestAnimationFrame(run)
    return () => window.cancelAnimationFrame(raf)
  }, [selectedId, panelOpen, rf, wrap, positions, onMoved])
  return null
}

/** 小地圖：整張圖都在「面板以外的可見範圍」內就隱藏；有節點被蓋住或超出畫面才顯示 */
function MiniGate({ size, positions, panelW }: { size: Size; positions: Positions; panelW: number }) {
  const [x, y, zoom] = useStore((s) => s.transform)
  const b = bounds(positions)
  if (size.w > 0 && size.w <= NARROW_W) return null
  if (b && size.w > 0 && size.h > 0) {
    const seen = size.w - (size.w > STACK_W ? panelW : 0)
    const l = x + b.minX * zoom
    const t = y + b.minY * zoom
    if (l >= -0.5 && t >= -0.5 && l + b.bw * zoom <= seen + 0.5 && t + b.bh * zoom <= size.h + 0.5) return null
  }
  return <MiniMap aria-hidden="true" style={{ width: MINI.w, height: MINI.h, margin: MINI.edge }} pannable zoomable nodeStrokeWidth={2} nodeColor={MINI_FILL} nodeStrokeColor={MINI_STROKE} />
}

function ZoomBar({ wrap, positions, size }: { wrap: Wrap; positions: Positions; size: Size }) {
  const rf = useReactFlow()
  const zoom = useStore((s) => s.transform[2])
  if (size.w > 0 && size.w <= NARROW_W) return null
  const low = zoom < READABLE - 0.0005
  return (
    <>
      {low && (
        <div className="zfnote">
          <span>縮小檢視中，文字偏小</span>
          <button type="button" className="btn secondary sm" onClick={() => place(rf, wrap, positions)}>
            回到可讀大小
          </button>
        </div>
      )}
      <div className="zoombar" role="group" aria-label="縮放">
        <button type="button" className="btn secondary sm" aria-label="縮小" onClick={() => void rf.zoomOut()}>
          −
        </button>
        <span className={`pct${low ? ' low' : ''}`} aria-live="off">
          {Math.round(zoom * 100)}%
        </span>
        <button type="button" className="btn secondary sm" aria-label="放大" onClick={() => void rf.zoomIn()}>
          +
        </button>
        <button type="button" className="btn secondary sm" aria-label="看全圖" title="看全圖：整張圖縮進畫布" onClick={() => fitAll(rf, wrap, positions)}>
          ⤢
        </button>
        <button type="button" className="btn secondary sm" aria-label="符合寬度" title="符合寬度：放大到填滿畫布寬（最大 100%）" onClick={() => fitWidth(rf, wrap, positions)}>
          ⇔
        </button>
      </div>
    </>
  )
}

export function Canvas({
  def,
  layout,
  selectedId,
  errorIds,
  panelOpen = false,
  onSelect,
  onMove,
}: {
  def: Def
  layout: Layout | null
  selectedId: string | null
  errorIds: ReadonlySet<string>
  /** 右側面板（浮在畫布上）是否展開：展開時選取節點會把被蓋住的節點平移出來 */
  panelOpen?: boolean
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
  const size = useBoxSize(wrap)
  const [moved, setMoved] = useState<string | null>(null)
  useEffect(() => {
    if (!moved) return
    const t = window.setTimeout(() => setMoved(null), 2000)
    return () => window.clearTimeout(t)
  }, [moved])
  const movedNode = moved ? def.nodes.find((n) => n.id === moved) : null

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
          defaultViewport={{ x: PAD, y: PAD, zoom: AUTO_MIN }}
          minZoom={MIN_ZOOM}
          maxZoom={MAX_ZOOM}
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
          <MiniGate size={size} positions={positions} panelW={panelOpen ? PANEL_FALLBACK_W : 0} />
        </ReactFlow>
        <ZoomBar wrap={wrap} positions={positions} size={size} />
        <FitOnChange signature={signature} wrap={wrap} positions={positions} />
        <RevealSelected selectedId={selectedId} panelOpen={panelOpen} wrap={wrap} positions={positions} onMoved={setMoved} />
        {movedNode && size.w > STACK_W && (
          <p className="zfhint" aria-live="polite">
            已移動畫面，讓『{displayName(movedNode)}』露出來
          </p>
        )}
      </div>
    </ReactFlowProvider>
  )
}
