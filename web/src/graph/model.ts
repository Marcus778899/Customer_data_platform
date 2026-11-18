import { Graph, layout } from '@dagrejs/dagre'
import type { EnrollmentStatus, JourneyVersion, NodeStat, TraceEntry } from '../api/types'
import { TERMINAL } from '../format'
import { nodeTypeText } from '../plain'

/** 尺寸對應 tokens.css 的 --node-width／--node-height、--node-pitch（圖上相鄰欄中心距） */
export const NODE_W = 148
export const NODE_H = 72
export const RANK_SEP = 32
export const NODE_SEP = 32
export const MARGIN = 16

export type Category = 'source' | 'wait' | 'branch' | 'send' | 'action' | 'end'
const CATEGORY: Record<string, [Category, string]> = {
  StartNode: ['source', '來源'],
  EventSourceNode: ['source', '來源'],
  WaitDelayNode: ['wait', '等待'],
  WaitForEventNode: ['wait', '等待'],
  ConditionNode: ['branch', '分流'],
  SplitTestNode: ['branch', '分流'],
  MergeNode: ['branch', '分流'],
  SendMessageNode: ['send', '發送'],
  EndNode: ['end', '出口'],
  ExitNode: ['end', '出口'],
  GoalNode: ['end', '出口'],
}
export const categoryOf = (type: string): [Category, string] => CATEGORY[type] ?? ['action', '動作']

/** 文字版節點清單用的簡短類型說明 */
const TYPE_NOTE: Record<string, string> = { EventSourceNode: '事件來源', SendMessageNode: '發送' }

export type NodeState = 'visited' | 'current' | 'unvisited' | 'plain'

export interface FlowNodeData extends Record<string, unknown> {
  name: string
  type: string
  /** 類型白話名（顯示用；type 保留程式名供比對） */
  typeText?: string
  category: Category
  categoryLabel: string
  state: NodeState
  /** 軌跡：這個節點被經過幾次（>= 2 才標示） */
  passes?: number
  /** 軌跡：目前位置是第幾次進入 */
  visit?: number
  /** 節點統計（旅程頁）；沒有 = 不顯示人數 */
  stat?: { entered: number; current: number }
}
export interface PlacedNode {
  id: string
  x: number
  y: number
  data: FlowNodeData
}
export interface PlacedEdge {
  id: string
  source: string
  target: string
  taken: boolean
  /** 有軌跡且這條線沒走過 */
  skipped: boolean
  back: boolean
}
export interface GraphModel {
  nodes: PlacedNode[]
  edges: PlacedEdge[]
  width: number
  height: number
  /** 文字版節點清單（依連線順序） */
  textOrder: string[]
}

export interface TraceOverlay {
  visited: ReadonlySet<string>
  current: string | null
  taken: ReadonlySet<string>
  /** 各節點在軌跡裡出現的次數 */
  passes?: ReadonlyMap<string, number>
  /** 目前位置是第幾次進入（journey-engine §4.5 visit） */
  currentVisit?: number
}

/** outputs 缺漏或為 null（舊資料、終端節點）視為空物件 */
export const outsOf = (n: { outputs?: unknown } | null | undefined): Record<string, string> =>
  n && typeof n.outputs === 'object' && n.outputs !== null ? (n.outputs as Record<string, string>) : {}

export const edgeId = (source: string, port: string) => `${source}:${port}`

/** 軌跡與定義對不上（node_id 不在定義裡）→ null，呼叫端退回逐步紀錄（journey-engine §4.5，不推測） */
export function deriveOverlay(
  def: JourneyVersion,
  trace: TraceEntry[],
  status: EnrollmentStatus,
): TraceOverlay | null {
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  if (trace.some((t) => !byId.has(t.node_id))) return null
  const visited = new Set(trace.map((t) => t.node_id))
  const taken = new Set<string>()
  if (trace.length > 0 && byId.has(def.entry_node)) {
    visited.add(def.entry_node)
    for (const [port, target] of Object.entries(outsOf(byId.get(def.entry_node)))) {
      if (target === trace[0].node_id) taken.add(edgeId(def.entry_node, port))
    }
  }
  trace.forEach((t, i) => {
    if (t.out_port === null) return
    const target = outsOf(byId.get(t.node_id))[t.out_port]
    if (target !== undefined && trace[i + 1]?.node_id === target) taken.add(edgeId(t.node_id, t.out_port))
  })
  let current: string | null = null
  if (!TERMINAL.has(status)) {
    const open = [...trace].reverse().find((t) => t.exited_at === null)
    current = open?.node_id ?? null
  }
  const passes = new Map<string, number>()
  for (const t of trace) passes.set(t.node_id, (passes.get(t.node_id) ?? 0) + 1)
  return { visited, current, taken, passes, currentVisit: current ? (passes.get(current) ?? 1) : 1 }
}

/** 位在迴圈裡的節點（能沿出口走回自己） */
export function loopNodeIds(def: JourneyVersion): Set<string> {
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  const out = new Set<string>()
  for (const n of def.nodes) {
    const seen = new Set<string>()
    const stack = Object.values(outsOf(n))
    while (stack.length) {
      const id = stack.pop()!
      if (id === n.id) {
        out.add(n.id)
        break
      }
      if (seen.has(id) || !byId.has(id)) continue
      seen.add(id)
      stack.push(...Object.values(outsOf(byId.get(id))))
    }
  }
  return out
}

export function buildModel(def: JourneyVersion, overlay: TraceOverlay | null, stats?: readonly NodeStat[]): GraphModel {
  const statById = new Map((stats ?? []).map((x) => [x.node_id, x]))
  const ids = new Set(def.nodes.map((n) => n.id))
  const g = new Graph()
  g.setGraph({ rankdir: 'LR', nodesep: NODE_SEP, ranksep: RANK_SEP, marginx: MARGIN, marginy: MARGIN, acyclicer: 'greedy' })
  g.setDefaultEdgeLabel(() => ({}))
  for (const n of def.nodes) g.setNode(n.id, { width: NODE_W, height: NODE_H })
  const rawEdges: { id: string; source: string; target: string }[] = []
  for (const n of def.nodes) {
    for (const [port, target] of Object.entries(outsOf(n))) {
      if (!ids.has(target)) continue
      rawEdges.push({ id: edgeId(n.id, port), source: n.id, target })
      g.setEdge(n.id, target)
    }
  }
  layout(g)

  // 座標：layout 有列的用 layout（左上角），沒列的用 dagre（中心點換左上角）
  const saved = def.layout?.nodes ?? {}
  const pos = new Map<string, { x: number; y: number }>()
  for (const n of def.nodes) {
    const s = saved[n.id]
    if (s && Number.isFinite(s.x) && Number.isFinite(s.y)) pos.set(n.id, { x: s.x, y: s.y })
    else {
      const p = g.node(n.id)
      pos.set(n.id, { x: p.x - NODE_W / 2, y: p.y - NODE_H / 2 })
    }
  }

  const nodes: PlacedNode[] = def.nodes.map((n) => {
    const [category, categoryLabel] = categoryOf(n.type)
    let state: NodeState = 'plain'
    if (overlay) state = overlay.current === n.id ? 'current' : overlay.visited.has(n.id) ? 'visited' : 'unvisited'
    const data: FlowNodeData = { name: n.name ?? nodeTypeText(n.type), type: n.type, typeText: nodeTypeText(n.type), category, categoryLabel, state }
    const passes = overlay?.passes?.get(n.id) ?? 0
    if (passes >= 2) data.passes = passes
    if (state === 'current' && (overlay?.currentVisit ?? 1) >= 2) data.visit = overlay!.currentVisit
    const st = statById.get(n.id)
    if (st) data.stat = { entered: st.entered, current: st.current }
    return { id: n.id, ...pos.get(n.id)!, data }
  })
  const edges: PlacedEdge[] = rawEdges.map((e) => ({
    ...e,
    taken: overlay?.taken.has(e.id) ?? false,
    skipped: overlay !== null && !overlay.taken.has(e.id),
    back: pos.get(e.target)!.x <= pos.get(e.source)!.x,
  }))
  const width = Math.max(...nodes.map((n) => n.x + NODE_W), 0) + MARGIN
  const height = Math.max(...nodes.map((n) => n.y + NODE_H), 0) + MARGIN * 2
  return { nodes, edges, width, height, textOrder: textOrder(def) }
}

function textOrder(def: JourneyVersion): string[] {
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  const seen = new Set<string>()
  const out: string[] = []
  const queue = [def.entry_node, ...def.nodes.map((n) => n.id)]
  while (queue.length) {
    const id = queue.shift()!
    const n = byId.get(id)
    if (!n || seen.has(id)) continue
    seen.add(id)
    const note = TYPE_NOTE[n.type]
    out.push(`${n.name ?? n.type}${note ? `（${note}）` : ''}`)
    queue.unshift(...Object.values(outsOf(n)))
  }
  return out
}
