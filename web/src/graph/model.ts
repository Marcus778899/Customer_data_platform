import { Graph, layout } from '@dagrejs/dagre'
import type { EnrollmentStatus, JourneyVersion, TraceEntry } from '../api/types'
import { TERMINAL } from '../format'

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
  category: Category
  categoryLabel: string
  state: NodeState
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
}

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
    for (const [port, target] of Object.entries(byId.get(def.entry_node)!.outputs)) {
      if (target === trace[0].node_id) taken.add(edgeId(def.entry_node, port))
    }
  }
  trace.forEach((t, i) => {
    if (t.out_port === null) return
    const target = byId.get(t.node_id)!.outputs[t.out_port]
    if (target !== undefined && trace[i + 1]?.node_id === target) taken.add(edgeId(t.node_id, t.out_port))
  })
  let current: string | null = null
  if (!TERMINAL.has(status)) {
    const open = [...trace].reverse().find((t) => t.exited_at === null)
    current = open?.node_id ?? null
  }
  return { visited, current, taken }
}

export function buildModel(def: JourneyVersion, overlay: TraceOverlay | null): GraphModel {
  const ids = new Set(def.nodes.map((n) => n.id))
  const g = new Graph()
  g.setGraph({ rankdir: 'LR', nodesep: NODE_SEP, ranksep: RANK_SEP, marginx: MARGIN, marginy: MARGIN, acyclicer: 'greedy' })
  g.setDefaultEdgeLabel(() => ({}))
  for (const n of def.nodes) g.setNode(n.id, { width: NODE_W, height: NODE_H })
  const rawEdges: { id: string; source: string; target: string }[] = []
  for (const n of def.nodes) {
    for (const [port, target] of Object.entries(n.outputs)) {
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
    return {
      id: n.id,
      ...pos.get(n.id)!,
      data: { name: n.name ?? n.type, type: n.type, category, categoryLabel, state },
    }
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
    queue.unshift(...Object.values(n.outputs))
  }
  return out
}
