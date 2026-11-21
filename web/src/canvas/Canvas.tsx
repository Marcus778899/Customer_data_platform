// 編輯畫布：@xyflow/react 負責繪製與縮放；節點是 <button aria-pressed>，邊與裝飾 aria-hidden。
// 連線有三種做法，效果相同：拉線（出口圓點拖到節點，放開在空白處跳「接上新節點」選單）、
// 出口圓點按 Enter（焦點移到面板的「下一步」下拉）、面板「下一步」下拉本身。節點可拖曳，座標存進 layout。
import {
  BaseEdge,
  EdgeLabelRenderer,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useConnection,
  useReactFlow,
  useStore,
  type Connection,
  type Edge,
  type EdgeProps,
  type FinalConnectionState,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { HANDLE_R, outletY, pickZoom } from '../graph/card'
import { Icon } from '../components/CanvasIcons'
import { placeLinkMenu, type LinkMenu } from './menu'
import { placeRemove, routeEdge, type Pt, type Route } from '../graph/route'
import {
  canTarget,
  CARD_H,
  CARD_W,
  categoryOf,
  displayName,
  fillLayout,
  NODE_MIME,
  outputSpecs,
  PALETTE,
  portName,
  summaryOf,
  targetsOf,
  topologyKey,
  typeLabel,
  type Def,
  type Layout,
} from './model'

interface PortView {
  port: string
  y: number
  connected: boolean
  optional: boolean
  label: string
  /** 多個出口才顯示出口名 */
  multi: boolean
  aria: string
}

interface CardData extends Record<string, unknown> {
  name: string
  categoryLabel: string
  category: string
  summary: string
  selected: boolean
  hasError: boolean
  locked: boolean
  targetable: boolean
  ports: PortView[]
  onSelect: (id: string) => void
  onPort: (id: string, port: string) => void
  id: string
}
type CardNode = Node<CardData, 'card'>

const CardView = memo(function CardView({ data }: NodeProps<CardNode>) {
  // 拉線中：來源自己與不能被連的節點（進入條件）變淡，其他節點游標移上綠框
  const linkFrom = useConnection((c) => (c.inProgress ? c.fromNode.id : null))
  const nogo = linkFrom !== null && (linkFrom === data.id || !data.targetable)
  return (
    <div className={`cwrap${nogo ? ' nogo' : ''}`}>
      <button
        type="button"
        className={`cnode cat-${data.category}${data.selected ? ' selected' : ''}${data.hasError ? ' has-error' : ''}`}
        aria-pressed={data.selected}
        onClick={() => data.onSelect(data.id)}
      >
        <span className="n-head">
          <span className="n-cat">{data.categoryLabel}</span>
          {data.hasError && <span className="n-err">有錯誤</span>}
        </span>
        <span className="n-name" title={data.name}>{data.name}</span>
        <span className="n-sum">{data.summary}</span>
      </button>
      {/* 整張卡都是入口：拉線放開在卡片上任何位置都連到這個節點 */}
      <Handle id="in" type="target" position={Position.Left} className="in-cover nodrag nopan" isConnectableStart={false} isConnectable={!data.locked && data.targetable} />
      {data.ports.map((p) => (
        <Handle
          key={p.port}
          id={p.port}
          type="source"
          position={Position.Right}
          className="out-hd nodrag nopan"
          style={{ top: p.y }}
          isConnectableEnd={false}
          isConnectable={!data.locked}
        >
          <button
            type="button"
            className="out-btn"
            data-connected={p.connected ? 'true' : 'false'}
            data-optional={p.optional ? 'true' : undefined}
            data-label={p.multi ? p.label : undefined}
            aria-label={p.aria}
            onClick={(e) => e.stopPropagation()}
            onKeyDown={(e) => {
              if (e.key !== 'Enter' && e.key !== ' ') return
              e.preventDefault()
              e.stopPropagation()
              data.onPort(data.id, p.port)
            }}
          />
        </Handle>
      ))}
    </div>
  )
})

interface LinkData extends Record<string, unknown> {
  label: string
  route: Route
  selected: boolean
  /** 可見範圍（畫面像素）右緣：面板浮在畫布右側時要扣掉面板寬 */
  visibleRight: number
  onRemove: (id: string) => void
  locked: boolean
}

const REMOVE_ROUND = 44
const REMOVE_PILL = { w: 120, h: 44 }
const LONG_EDGE = 160

function LinkView(props: EdgeProps<Edge<LinkData>>) {
  const { id, data } = props
  const [tx, ty, zoom] = useStore((s) => s.transform)
  const w = useStore((s) => s.width)
  const h = useStore((s) => s.height)
  if (!data) return null
  const { route } = data
  const marker = `mk-${id}`
  let button: { x: number; y: number; long: boolean } | null = null
  if (data.selected) {
    const long = route.length * zoom >= LONG_EDGE
    const size = long ? REMOVE_PILL : { w: REMOVE_ROUND, h: REMOVE_ROUND }
    const mid = { x: route.mid.x * zoom + tx, y: route.mid.y * zoom + ty }
    const box = { left: 8, top: 8, right: Math.max(8, (data.visibleRight || w) - 8), bottom: Math.max(8, h - 8) }
    const at = w > 0 && h > 0 ? placeRemove(mid, route.side, long, box, size) : mid
    button = { x: (at.x - tx) / zoom, y: (at.y - ty) / zoom, long }
  }
  return (
    <>
      <g aria-hidden="true">
        <BaseEdge id={id} path={route.d} className={`e${route.kind === 'forward' ? '' : ' back'}${data.selected ? ' sel' : ''}`} markerEnd={`url(#${marker})`} interactionWidth={24} />
        <defs>
          <marker id={marker} className={`mk${data.selected ? ' sel' : ''}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="10" markerHeight="10" markerUnits="userSpaceOnUse" orient="auto">
            <path d="M0 0L10 5L0 10z" />
          </marker>
        </defs>
        {data.label && (
          <text className="e-label" x={route.mid.x} y={route.mid.y - 6} textAnchor="middle">
            {data.label}
          </text>
        )}
      </g>
      {button && !data.locked && (
        <EdgeLabelRenderer>
          <button
            type="button"
            className={`edel nodrag nopan${button.long ? '' : ' round'}`}
            style={{ transform: `translate(-50%, -50%) translate(${button.x}px, ${button.y}px) scale(${1 / zoom})` }}
            aria-label="移除連線"
            title="移除連線"
            onClick={(e) => {
              e.stopPropagation()
              data.onRemove(id)
            }}
          >
            <Icon name="trash" />
            {button.long && '移除連線'}
          </button>
        </EdgeLabelRenderer>
      )}
    </>
  )
}

const MINI_FILL = () => 'var(--color-border-strong)'
const MINI_STROKE = () => 'var(--color-text-muted)'
const nodeTypes = { card: CardView }
const edgeTypes = { link: LinkView }

/** 小地圖尺寸與邊距（右下角） */
const MINI = { w: 160, h: 100, edge: 15 } as const
const PAD = 24
/** 自動縮放（載入、新增節點、接線）規則見 graph/card.ts pickZoom：75%～100%，手機最高 125%；只有「看全圖」可以更小 */
const AUTO_MIN = 0.75
const FIT_MAX = 0.8
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
 * 自動縮放（F-31、node-card-unify）：整張放得下就 100% 置中；放不下就從最左（開始）節點起靠左，
 * 縮放取 75%～100% 中右緣不切在卡片文字中間的最大值（pickZoom）。手機（窄畫布）圖太高時，捲到「開始」那一列置中。
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
  const xs = Object.values(positions()).map((p) => p.x)
  const zoom = pickZoom(availW, b.bw, (z) => b.minX + (w - PAD) / z, xs)
  const x = b.bw * zoom <= availW ? (w - b.bw * zoom) / 2 - b.minX * zoom : PAD - b.minX * zoom
  let y = b.bh * zoom <= availH ? PAD + (availH - b.bh * zoom) / 2 - b.minY * zoom : PAD - b.minY * zoom
  if (w <= NARROW_W && b.bh * zoom > availH) {
    // 主幹列＝最左邊（開始）那張卡所在的列
    const start = Object.values(positions()).reduce((a, p) => (p.x < a.x ? p : a))
    const centered = h / 2 - (start.y + CARD_H / 2) * zoom
    y = Math.min(PAD - b.minY * zoom, Math.max(h - PAD - (b.minY + b.bh) * zoom, centered))
  }
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
  const zoom = Math.max(MIN_ZOOM, Math.min(FIT_MAX, availW / b.bw, availH / b.bh))
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
      // 出口圓點突出卡片右緣 HANDLE_R，一起算進可見範圍
      const right = left + (CARD_W + HANDLE_R) * vp.zoom
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

/** 右緣 48px 漸層：還有節點在畫面右緣之外才顯示（整張放得下、捲到底就消失） */
function RightFade({ size, positions }: { size: Size; positions: Positions }) {
  const [x, , zoom] = useStore((s) => s.transform)
  const b = bounds(positions)
  const more = !!b && size.w > 0 && x + (b.minX + b.bw) * zoom > size.w + 0.5
  return <div className="cvfade" hidden={!more} aria-hidden="true" />
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

/** 首次提示是否已看過：記在 localStorage（讀寫失敗就當沒看過、不記） */
const HINT_KEY = 'cdp.canvas.connect-hint'
const hintSeen = () => {
  try {
    return localStorage.getItem(HINT_KEY) === '1'
  } catch {
    return false
  }
}
const markHintSeen = () => {
  try {
    localStorage.setItem(HINT_KEY, '1')
  } catch {
    /* 不能記就下次再顯示 */
  }
}

interface CanvasProps {
  def: Def
  layout: Layout | null
  selectedId: string | null
  selectedEdge: string | null
  errorIds: ReadonlySet<string>
  /** 右側面板（浮在畫布上）是否展開：展開時選取節點會把被蓋住的節點平移出來 */
  panelOpen?: boolean
  /** 送審中：不能連線、移動、刪線 */
  locked?: boolean
  onSelect: (id: string) => void
  onSelectEdge: (id: string | null) => void
  onMove: (id: string, pos: { x: number; y: number }) => void
  /** 拉線連到節點 */
  onConnect: (source: string, port: string, target: string) => void
  onRemoveLink: (edgeId: string) => void
  /** 出口圓點按 Enter：焦點移到面板的「下一步」下拉 */
  onPortKey: (id: string, port: string) => void
  /** 從節點庫拖進來，或放開在空白處選了節點：pos 是卡片左上角（流程座標），from 有值就連到該出口 */
  onAddAt: (type: string, pos: Pt, from?: { id: string; port: string }) => void
}

function CanvasBody({
  def,
  layout,
  selectedId,
  selectedEdge,
  errorIds,
  panelOpen = false,
  locked = false,
  onSelect,
  onSelectEdge,
  onMove,
  onConnect,
  onRemoveLink,
  onPortKey,
  onAddAt,
}: CanvasProps) {
  const rf = useReactFlow()
  const linking = useConnection((c) => c.inProgress)
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

  const [hint, setHint] = useState(() => !hintSeen())
  const dismissHint = useCallback(() => {
    markHintSeen()
    setHint(false)
  }, [])
  const [menu, setMenu] = useState<LinkMenu | null>(null)
  const [over, setOver] = useState(false)
  const pressAt = useRef<Pt | null>(null)

  const panelW = panelOpen && size.w > STACK_W ? PANEL_FALLBACK_W : 0

  const nodes = useMemo<CardNode[]>(
    () =>
      def.nodes.map((n) => {
        const meta = categoryOf(n.type)
        const specs = outputSpecs(n)
        const name = displayName(n)
        const used = new Set(targetsOf(n).map((t) => t.port))
        const ports: PortView[] = specs.map((s, i) => ({
          port: s.port,
          y: outletY(i, specs.length),
          connected: used.has(s.port),
          optional: !!s.optional,
          label: portName(s),
          multi: specs.length > 1,
          aria: specs.length > 1 ? `從『${name}』的『${portName(s)}』出口拉線到下一步` : `從『${name}』的出口拉線到下一步`,
        }))
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
            { id: 'in', type: 'target', position: Position.Left, x: 0, y: CARD_H / 2, width: 1, height: 1 },
            ...ports.map((p) => ({ id: p.port, type: 'source' as const, position: Position.Right, x: CARD_W, y: p.y, width: 1, height: 1 })),
          ],
          data: {
            id: n.id,
            name,
            category: meta.category,
            categoryLabel: meta.label,
            summary: summaryOf(n),
            selected: n.id === selectedId,
            hasError: errorIds.has(n.id),
            locked,
            targetable: canTarget(n),
            ports,
            onSelect,
            onPort: onPortKey,
          },
        }
      }),
    [def, full, drag, selectedId, errorIds, locked, onSelect, onPortKey],
  )

  const edges = useMemo<Edge<LinkData>[]>(() => {
    const ids = new Set(def.nodes.map((n) => n.id))
    const cards = def.nodes.map((n) => drag[n.id] ?? full.nodes[n.id]).filter((p): p is Pt => !!p)
    const out: Edge<LinkData>[] = []
    for (const n of def.nodes) {
      const specs = outputSpecs(n)
      const multi = specs.length > 1
      for (const t of targetsOf(n)) {
        if (!ids.has(t.target)) continue
        const a = drag[n.id] ?? full.nodes[n.id] ?? { x: 0, y: 0 }
        const b = drag[t.target] ?? full.nodes[t.target] ?? { x: 0, y: 0 }
        const idx = specs.findIndex((s) => s.port === t.port)
        const from = { x: a.x + CARD_W, y: a.y + (idx >= 0 ? outletY(idx, specs.length) : CARD_H / 2) }
        const id = `${n.id}:${t.port}`
        out.push({
          id,
          source: n.id,
          sourceHandle: idx >= 0 ? t.port : undefined,
          target: t.target,
          targetHandle: 'in',
          type: 'link',
          selectable: false,
          focusable: false,
          zIndex: selectedEdge === id ? 10 : 0,
          data: {
            label: multi && idx >= 0 ? portName(specs[idx]) : '',
            route: routeEdge(from, a, b, cards),
            selected: selectedEdge === id,
            visibleRight: size.w - panelW,
            onRemove: onRemoveLink,
            locked,
          },
        })
      }
    }
    return out
  }, [def, full, drag, selectedEdge, size.w, panelW, locked, onRemoveLink])

  const isValidConnection = useCallback(
    (c: Edge | Connection) => c.source !== c.target && canTarget(def.nodes.find((n) => n.id === c.target)),
    [def],
  )
  const handleConnect = useCallback(
    (c: Connection) => {
      if (!c.sourceHandle || !isValidConnection(c)) return
      dismissHint()
      onConnect(c.source, c.sourceHandle, c.target)
    },
    [isValidConnection, dismissHint, onConnect],
  )
  const handleConnectEnd = (event: MouseEvent | TouchEvent, state: FinalConnectionState) => {
    const press = pressAt.current
    pressAt.current = null
    if (locked || state.isValid || state.toNode || !state.fromNode || !state.fromHandle?.id) return
    const pt = 'changedTouches' in event ? event.changedTouches[0] : event
    if (!pt) return
    const fromId = state.fromNode.id
    const port = state.fromHandle.id
    const rect = wrap.current?.getBoundingClientRect()
    const specs = outputSpecs(def.nodes.find((n) => n.id === fromId) ?? { id: fromId, type: '', name: null, config: {}, outputs: {} })
    const next = placeLinkMenu({
      press,
      pointer: { x: pt.clientX, y: pt.clientY },
      box: { left: rect?.left ?? 0, top: rect?.top ?? 0, w: wrap.current?.offsetWidth ?? 0, h: wrap.current?.offsetHeight ?? 0 },
      flow: rf.screenToFlowPosition({ x: pt.clientX, y: pt.clientY }),
      from: { id: fromId, port },
      node: fullRef.current.nodes[fromId],
      outputs: specs.length,
      index: Math.max(0, specs.findIndex((s) => s.port === port)),
      viewport: rf.getViewport(),
    })
    if (next) setMenu(next)
  }

  // 選單開著時：Esc 取消（先於 Editor 的 Esc，不順便取消選取）
  useEffect(() => {
    if (!menu) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopPropagation()
      setMenu(null)
    }
    document.addEventListener('keydown', onKey, true)
    return () => document.removeEventListener('keydown', onKey, true)
  }, [menu])
  const menuRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (menu) menuRef.current?.querySelector<HTMLButtonElement>('button')?.focus()
  }, [menu])

  const acceptsDrop = (e: React.DragEvent) => !locked && [...(e.dataTransfer?.types ?? [])].includes(NODE_MIME)

  return (
    <div
      className={`canvas-wrap${linking ? ' linking' : ''}${menu ? ' menuopen' : ''}${over ? ' dragover' : ''}`}
      ref={wrap}
      onDragOver={(e) => {
        if (!acceptsDrop(e)) return
        e.preventDefault()
        setOver(true)
      }}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target) setOver(false)
      }}
      onDrop={(e) => {
        setOver(false)
        const type = e.dataTransfer?.getData(NODE_MIME)
        if (!type || locked) return
        e.preventDefault()
        const at = rf.screenToFlowPosition({ x: e.clientX, y: e.clientY })
        onAddAt(type, { x: Math.round(at.x - CARD_W / 2), y: Math.round(at.y - CARD_H / 2) })
      }}
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        defaultViewport={{ x: PAD, y: PAD, zoom: AUTO_MIN }}
        minZoom={MIN_ZOOM}
        maxZoom={MAX_ZOOM}
        nodesConnectable={!locked}
        nodeDragThreshold={4}
        connectionRadius={24}
        isValidConnection={isValidConnection}
        onConnect={handleConnect}
        onConnectStart={(e) => {
          const pt = 'touches' in e ? e.touches[0] : e
          pressAt.current = pt ? { x: pt.clientX, y: pt.clientY } : null
        }}
        onConnectEnd={handleConnectEnd}
        onEdgeClick={(_, edge) => onSelectEdge(edge.id)}
        onPaneClick={() => {
          onSelectEdge(null)
          setMenu(null)
        }}
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
        <MiniGate size={size} positions={positions} panelW={panelW} />
      </ReactFlow>
      <RightFade size={size} positions={positions} />
      <ZoomBar wrap={wrap} positions={positions} size={size} />
      <FitOnChange signature={signature} wrap={wrap} positions={positions} />
      <RevealSelected selectedId={selectedId} panelOpen={panelOpen} wrap={wrap} positions={positions} onMoved={setMoved} />
      {movedNode && size.w > STACK_W && (
        <p className="zfhint" aria-live="polite">
          已移動畫面，讓『{displayName(movedNode)}』露出來
        </p>
      )}
      {over && <div className="dropmsg">放開就新增在這裡</div>}
      {/* 首次提示：放在畫布容器內、viewport 之外，不跟著平移縮放 */}
      {hint && !menu && (
        <div className="coach">
          <span>從節點右邊的圓點按住、拉到下一個節點就連好了。連錯了？點那條線，按「移除連線」。</span>
          <button type="button" onClick={dismissHint}>
            知道了
          </button>
        </div>
      )}
      {menu && (
        <>
          <svg className="menu-line" aria-hidden="true">
            <path d={`M${menu.start.x} ${menu.start.y} L${menu.drop.x} ${menu.drop.y}`} />
          </svg>
          <div className="cmenu" ref={menuRef} role="group" aria-label="接上新節點" style={{ left: menu.x, top: menu.y }}>
            <h4>接上新節點</h4>
            {PALETTE.flatMap((g) => g.items).map((it) => (
              <button
                key={it.type}
                type="button"
                className={`m cat-${categoryOf(it.type).category}`}
                onClick={() => {
                  onAddAt(it.type, { x: Math.round(menu.flow.x), y: Math.round(menu.flow.y - CARD_H / 2) }, menu.from)
                  setMenu(null)
                }}
              >
                {typeLabel(it.type)}
              </button>
            ))}
            <button type="button" className="m cancel" onClick={() => setMenu(null)}>
              取消
            </button>
          </div>
        </>
      )}
    </div>
  )
}

export function Canvas(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasBody {...props} />
    </ReactFlowProvider>
  )
}
