// 唯讀旅程圖：@xyflow/react 負責節點與連線繪製，座標來自 model.ts（layout 或 dagre 由左到右）。
// 縮放用 CSS scale（初始縮放規則見 card.ts），超出容器時容器自己左右捲動；縮放列與「右邊還有 N 個節點」提示在圖下方同一排。
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
import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { CARD_W, pickZoom } from './card'
import { type FlowNodeData, type GraphModel } from './model'

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
    data.typeText ?? data.type,
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
      <span className="n-name" title={data.name}>{data.name}</span>
      {data.stat ? (
        <span className="n-stat">
          <b>{fmtCount(data.stat.entered)}</b> 人數 <b>{fmtCount(data.stat.current)}</b> 目前
        </span>
      ) : (
        <span className="n-type">{visit ?? (data.typeText === data.name ? '' : (data.typeText ?? data.type))}</span>
      )}
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </button>
  )
})

interface FlowEdgeData extends Record<string, unknown> {
  taken: boolean
  skipped: boolean
  back: boolean
  cardH: number
}

function FlowEdgeView(props: EdgeProps<Edge<FlowEdgeData>>) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data } = props
  let path: string
  let label: { x: number; y: number } | null = null
  if (data?.back) {
    // 回邊：從節點下方繞回，點狀線
    const y = Math.max(sourceY, targetY) + data.cardH / 2 + 20
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

/** 量不到容器寬度時（測試環境、尚未排版）的可用寬度：1440 寬扣掉側欄與邊距 */
const FALLBACK_WIDTH = 1160
/** 手動縮放範圍與每按一下的步進 */
const ZOOM_LOW = 0.25
const ZOOM_HIGH = 1.5
const ZOOM_STEP = 0.1
/** 縮到這個比例以下，百分比轉警告色（文字偏小） */
const READABLE = 0.7
/** 捲到底的容許誤差（px） */
const END_SLACK = 2

const clampZoom = (z: number) => Math.min(ZOOM_HIGH, Math.max(ZOOM_LOW, Math.round(z * 100) / 100))

/** 容器的可視寬度與目前水平捲動位置（容器改變、捲動時更新） */
function useScrollBox(ref: React.RefObject<HTMLElement | null>): { width: number; left: number } {
  const [box, setBox] = useState({ width: 0, left: 0 })
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const read = () => setBox((b) => (b.width === el.clientWidth && b.left === el.scrollLeft ? b : { width: el.clientWidth, left: el.scrollLeft }))
    read()
    el.addEventListener('scroll', read)
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(read)
    ro?.observe(el)
    return () => {
      el.removeEventListener('scroll', read)
      ro?.disconnect()
    }
  }, [ref])
  return { width: box.width || FALLBACK_WIDTH, left: box.left }
}

/** 空白處按住左右拖曳（滑鼠；觸控本來就能捲） */
function useDragScroll(ref: React.RefObject<HTMLElement | null>) {
  useEffect(() => {
    const el = ref.current
    if (!el) return
    let startX = 0
    let startLeft = 0
    let active = false
    const down = (e: PointerEvent) => {
      if (e.pointerType !== 'mouse' || e.button !== 0 || (e.target as Element).closest('button')) return
      active = true
      startX = e.clientX
      startLeft = el.scrollLeft
    }
    const move = (e: PointerEvent) => {
      if (active) el.scrollLeft = startLeft - (e.clientX - startX)
    }
    const up = () => {
      active = false
    }
    el.addEventListener('pointerdown', down)
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
    return () => {
      el.removeEventListener('pointerdown', down)
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
    }
  }, [ref])
}

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
  const cardH = model.cardH
  const nodes = useMemo<StepNode[]>(
    () =>
      model.nodes.map((n) => ({
        id: n.id,
        type: 'step',
        position: { x: n.x, y: n.y },
        data: { ...n.data, selected: n.id === selectedId, onToggle: () => onSelect?.(n.id === selectedId ? null : n.id) },
        width: CARD_W,
        height: cardH,
        draggable: false,
        selectable: false,
        focusable: false,
        // 先給接點位置，連線不必等瀏覽器量測就能畫出來
        handles: [
          { type: 'target', position: Position.Left, x: 0, y: cardH / 2, width: 1, height: 1 },
          { type: 'source', position: Position.Right, x: CARD_W, y: cardH / 2, width: 1, height: 1 },
        ],
      })),
    [model, selectedId, onSelect, cardH],
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
        data: { taken: e.taken, skipped: e.skipped, back: e.back, cardH },
      })),
    [model, cardH],
  )
  const wrap = useRef<HTMLDivElement>(null)
  const { width: cw, left } = useScrollBox(wrap)
  useDragScroll(wrap)

  const auto = useMemo(() => pickZoom(cw - 2, model.width, (z) => cw / z, model.nodes.map((n) => n.x)), [cw, model])
  // 手動縮放只在同一張圖、同一個容器寬度內有效；換圖或容器改變就回到自動值
  const [manual, setManual] = useState<{ model: GraphModel; cw: number; z: number } | null>(null)
  const scale = manual && manual.model === model && manual.cw === cw ? manual.z : auto

  // 換圖或初始縮放改變：捲回最左；圖比框高時（手機）再捲到主幹列（開始節點那一列置中）
  useLayoutEffect(() => {
    const el = wrap.current
    if (!el) return
    el.scrollLeft = 0
    const start = model.nodes.reduce<(typeof model.nodes)[number] | null>((a, n) => (a === null || n.x < a.x ? n : a), null)
    if (start && el.scrollHeight > el.clientHeight) el.scrollTop = Math.max(0, (start.y + cardH / 2) * auto - el.clientHeight / 2)
  }, [model, auto, cardH])

  const contentW = model.width * scale
  const atEnd = contentW <= cw + 0.5 || left + cw >= contentW - END_SLACK
  const total = model.nodes.length
  const hidden = useMemo(() => {
    const right = (left + cw) / scale
    return model.nodes.filter((n) => n.x + CARD_W > right + 0.5).length
  }, [model, left, cw, scale])

  const setZoom = (z: number) => setManual({ model, cw, z: clampZoom(z) })
  const fitAll = () => {
    setZoom(Math.floor(((cw - 2) / model.width) * 100) / 100)
    window.requestAnimationFrame(() => {
      if (wrap.current) wrap.current.scrollLeft = 0
    })
  }
  const low = scale < READABLE - 0.0005

  return (
    <div className="graphwrap">
      <div className="graphbox">
        <div className="graph" role="group" aria-label={label} tabIndex={0} ref={wrap}>
          <div style={{ width: model.width * scale, height: model.height * scale }}>
            <div
              className="inner"
              style={{ width: model.width, height: model.height, transform: scale !== 1 ? `scale(${scale})` : undefined, transformOrigin: '0 0' }}
            >
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
        </div>
        <div className="cvfade" hidden={atEnd} aria-hidden="true" />
      </div>
      <div className="zrow">
        <div className="zbar" role="group" aria-label="縮放">
          <button type="button" className="btn secondary sm" aria-label="縮小" onClick={() => setZoom(scale - ZOOM_STEP)}>
            −
          </button>
          <span className={`pct${low ? ' low' : ''}`}>{Math.round(scale * 100)}%</span>
          <button type="button" className="btn secondary sm" aria-label="放大" onClick={() => setZoom(scale + ZOOM_STEP)}>
            +
          </button>
          <button type="button" className="btn secondary sm" aria-label="看全圖" title="看全圖：整張圖縮進畫面" onClick={fitAll}>
            看全圖
          </button>
        </div>
        <p className="zhint-ro muted" role="status" aria-live="off">
          {atEnd ? `已看到全部 ${total} 個節點` : `右邊還有 ${Math.max(1, hidden)} 個節點，左右滑動或按「看全圖」`}
        </p>
      </div>
    </div>
  )
}

