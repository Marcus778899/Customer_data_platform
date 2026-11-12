// 畫布編輯的純邏輯：節點型別表、預設值、連線、排版、迴圈偵測、Duration、條件樹。
// 契約：docs/contracts/journeys.md §3（節點 id 規則）、§4（節點與條件）；spec 03 §4。
import { Graph, layout as dagreLayout } from '@dagrejs/dagre'
import type { DraftDefinition, DraftLayout, JourneyNode } from '../api/types'

export type Def = DraftDefinition
export type DefNode = JourneyNode
export type Layout = DraftLayout

/** 畫布上節點卡的固定尺寸（px，RF 需要初始尺寸才能不等量測就畫出連線） */
export const CARD_W = 176
export const CARD_H = 88
const GAP_X = 72
const GAP_Y = 32

export type Category = 'source' | 'wait' | 'branch' | 'send' | 'end'

export interface OutputSpec {
  port: string
  label: string
  /** 沒連線不算錯誤的出口 */
  optional?: boolean
}

interface NodeMeta {
  label: string
  category: Category
  categoryLabel: string
  idPrefix: string
}

export const NODE_META: Record<string, NodeMeta> = {
  StartNode: { label: '開始', category: 'source', categoryLabel: '來源', idPrefix: 'start' },
  EventSourceNode: { label: '進入條件', category: 'source', categoryLabel: '來源', idPrefix: 'src' },
  SendMessageNode: { label: '訊息', category: 'send', categoryLabel: '發送', idPrefix: 'send' },
  WaitDelayNode: { label: '等待', category: 'wait', categoryLabel: '等待', idPrefix: 'wait' },
  WaitForEventNode: { label: '等事件', category: 'wait', categoryLabel: '等待', idPrefix: 'wait_event' },
  ConditionNode: { label: '條件', category: 'branch', categoryLabel: '分流', idPrefix: 'cond' },
  SplitTestNode: { label: 'A/B 分流', category: 'branch', categoryLabel: '分流', idPrefix: 'split' },
  MergeNode: { label: '合流', category: 'branch', categoryLabel: '分流', idPrefix: 'merge' },
  GoalNode: { label: '達標', category: 'end', categoryLabel: '出口', idPrefix: 'goal' },
  ExitNode: { label: '退出', category: 'end', categoryLabel: '出口', idPrefix: 'exit' },
  EndNode: { label: '結束', category: 'end', categoryLabel: '出口', idPrefix: 'end' },
}

/** 工具列的節點順序與分組（UI 規格：動作、分流、結束） */
export const PALETTE: { group: string; types: string[] }[] = [
  { group: '動作', types: ['SendMessageNode', 'WaitDelayNode', 'WaitForEventNode'] },
  { group: '分流', types: ['ConditionNode', 'SplitTestNode', 'MergeNode'] },
  { group: '結束', types: ['GoalNode', 'ExitNode', 'EndNode'] },
]

export const typeLabel = (type: string) => NODE_META[type]?.label ?? type
export const categoryOf = (type: string): { category: Category; label: string } => {
  const m = NODE_META[type]
  return m ? { category: m.category, label: m.categoryLabel } : { category: 'source', label: '節點' }
}

export const displayName = (n: DefNode) => (n.name && n.name.trim() ? n.name : typeLabel(n.type))

// ---------- Duration（PT30M／PT2H／P1D） ----------
export type DurationUnit = 'M' | 'H' | 'D'
export const UNIT_LABEL: Record<DurationUnit, string> = { M: '分鐘', H: '小時', D: '天' }

export function parseDuration(v: unknown): { amount: number; unit: DurationUnit } | null {
  if (typeof v !== 'string') return null
  let m = /^PT(\d+)M$/.exec(v)
  if (m) return { amount: Number(m[1]), unit: 'M' }
  m = /^PT(\d+)H$/.exec(v)
  if (m) return { amount: Number(m[1]), unit: 'H' }
  m = /^P(\d+)D$/.exec(v)
  if (m) return { amount: Number(m[1]), unit: 'D' }
  return null
}
export function buildDuration(amount: number, unit: DurationUnit): string {
  return unit === 'D' ? `P${amount}D` : `PT${amount}${unit}`
}
export function durationText(v: unknown): string {
  const d = parseDuration(v)
  return d ? `${d.amount} ${UNIT_LABEL[d.unit]}` : typeof v === 'string' && v ? v : '未設定'
}

// ---------- 條件樹（journeys.md §4） ----------
export interface CondLeaf {
  type: 'attribute'
  field: string
  operator: string
  value?: unknown
}
export interface CondGroup {
  op: 'AND' | 'OR' | 'NOT'
  children: Cond[]
}
export type Cond = CondLeaf | CondGroup
export const isGroup = (c: Cond): c is CondGroup => 'op' in c

/** 常見欄位的白話名（設計稿 ed-condition）；沒有對照的欄位顯示原代碼 */
const FIELD_LABEL: Record<string, string> = {
  tier: '會員等級',
  city: '居住城市',
  'trigger.properties.cart_total': '購物車金額',
}
export const fieldLabel = (code: string): string => FIELD_LABEL[code] ?? code
export const eventFieldLabel = (key: string): string => FIELD_LABEL[`trigger.properties.${key}`] ?? `事件屬性 ${key}`

export const OPERATORS: { value: string; label: string; noValue?: boolean }[] = [
  { value: 'EQ', label: '等於' },
  { value: 'NEQ', label: '不等於' },
  { value: 'IN', label: '屬於其中之一' },
  { value: 'NOT_IN', label: '不屬於其中任何一個' },
  { value: 'GT', label: '大於' },
  { value: 'GTE', label: '至少' },
  { value: 'LT', label: '小於' },
  { value: 'LTE', label: '至多' },
  { value: 'CONTAINS', label: '包含' },
  { value: 'IS_NULL', label: '沒有值', noValue: true },
  { value: 'IS_NOT_NULL', label: '有值', noValue: true },
]
export const MAX_COND_DEPTH = 5

export function condOf(node: DefNode): CondGroup {
  const c = node.config.condition
  return c && typeof c === 'object' && 'op' in c ? (c as CondGroup) : { op: 'AND', children: [] }
}

/** 把輸入的文字轉成條件值：比大小的運算子盡量轉數字，IN 類以逗號分隔成陣列 */
export function parseCondValue(operator: string, text: string): unknown {
  if (operator === 'IN' || operator === 'NOT_IN') {
    return text.split(',').map((s) => s.trim()).filter(Boolean)
  }
  if (['GT', 'GTE', 'LT', 'LTE'].includes(operator) && text.trim() !== '' && Number.isFinite(Number(text))) return Number(text)
  return text
}
export function condValueText(v: unknown): string {
  if (Array.isArray(v)) return v.join(',')
  return v === undefined || v === null ? '' : String(v)
}

// ---------- 節點出口 ----------
export function outputSpecs(n: DefNode): OutputSpec[] {
  switch (n.type) {
    case 'StartNode':
      return [{ port: 'next', label: '開始後' }]
    case 'EventSourceNode':
      return [{ port: 'next', label: '進入後' }]
    case 'WaitDelayNode':
      return [{ port: 'next', label: '等完之後' }]
    case 'WaitForEventNode':
      return n.config.timeout === null || n.config.timeout === undefined
        ? [{ port: 'occurred', label: '事件發生時' }]
        : [
            { port: 'occurred', label: '事件發生時' },
            { port: 'timeout', label: '逾時沒等到' },
          ]
    case 'ConditionNode':
      return [
        { port: 'yes', label: '是' },
        { port: 'no', label: '否' },
      ]
    case 'SendMessageNode':
      return [
        { port: 'sent', label: '送出後' },
        { port: 'skipped', label: '被略過時（例如客戶已退訂）', optional: true },
      ]
    case 'SplitTestNode':
      return variantsOf(n).map((v, i) => ({ port: v.port, label: v.label || `第 ${i + 1} 組` }))
    case 'MergeNode':
      return [{ port: 'next', label: '會合後' }]
    default:
      return []
  }
}

/** outputs 的值可能是陣列（StartNode.next，契約 journey-engine §3.1）；取第一個目標 */
export function targetsOf(n: DefNode): { port: string; target: string }[] {
  const out: { port: string; target: string }[] = []
  for (const [port, v] of Object.entries(n.outputs ?? {})) {
    const t = Array.isArray(v) ? v[0] : v
    if (typeof t === 'string' && t) out.push({ port, target: t })
  }
  return out
}
export const targetOfPort = (n: DefNode, port: string): string =>
  targetsOf(n).find((t) => t.port === port)?.target ?? ''

export interface Variant {
  port: string
  label: string
  weight: number
  is_control?: boolean
}
export function variantsOf(n: DefNode): Variant[] {
  const v = n.config.variants
  return Array.isArray(v) ? (v as Variant[]) : []
}

// ---------- 建立與修改 ----------
export const ID_RE = /^[a-z][a-z0-9_]*$/

export function newId(def: Def, type: string): string {
  const prefix = NODE_META[type]?.idPrefix ?? 'node'
  const used = new Set(def.nodes.map((n) => n.id))
  for (let i = 1; ; i++) {
    const id = `${prefix}_${i}`
    if (!used.has(id)) return id
  }
}

function defaultName(def: Def, type: string): string {
  const label = typeLabel(type)
  const names = new Set(def.nodes.map((n) => n.name))
  for (let i = def.nodes.filter((n) => n.type === type).length + 1; ; i++) {
    const name = `${label} ${i}`
    if (!names.has(name)) return name
  }
}

export function defaultConfig(type: string): Record<string, unknown> {
  switch (type) {
    case 'EventSourceNode':
      return { event_name: '' }
    case 'SendMessageNode':
      return { channel_id: '', template_id: '' }
    case 'WaitDelayNode':
      return { duration: 'PT1H' }
    case 'WaitForEventNode':
      return { event_name: '', timeout: 'P1D' }
    case 'ConditionNode':
      return { condition: { op: 'AND', children: [] } }
    case 'SplitTestNode':
      return {
        variants: [
          { port: 'v1', label: '對照組', weight: 50, is_control: true },
          { port: 'v2', label: '實驗組', weight: 50 },
        ],
        assignment_seed: 'STABLE',
      }
    case 'GoalNode':
      return { goal_id: '' }
    case 'ExitNode':
      return { reason: '' }
    default:
      return {}
  }
}

export function makeNode(def: Def, type: string): DefNode {
  return { id: newId(def, type), type, name: defaultName(def, type), config: defaultConfig(type), outputs: {} }
}

export const updateNode = (def: Def, id: string, fn: (n: DefNode) => DefNode): Def => ({
  ...def,
  nodes: def.nodes.map((n) => (n.id === id ? fn(n) : n)),
})

export function setOutput(n: DefNode, port: string, target: string): DefNode {
  const outputs = { ...n.outputs }
  if (target) outputs[port] = target
  else delete outputs[port]
  return { ...n, outputs }
}

/** 刪除節點與指向它的所有連線 */
export function removeNode(def: Def, id: string): Def {
  return {
    ...def,
    nodes: def.nodes
      .filter((n) => n.id !== id)
      .map((n) => {
        const outputs: Record<string, string> = {}
        for (const [port, v] of Object.entries(n.outputs ?? {})) {
          if (Array.isArray(v)) {
            const rest = (v as string[]).filter((t) => t !== id)
            if (rest.length) (outputs as Record<string, unknown>)[port] = rest
          } else if (v !== id) outputs[port] = v
        }
        return { ...n, outputs }
      }),
  }
}

export const incomingOf = (def: Def, id: string): DefNode[] =>
  def.nodes.filter((n) => targetsOf(n).some((t) => t.target === id))

/** 可當連線目標的節點：不含開始與進入條件（它們不接收連線） */
export const linkTargets = (def: Def, selfId: string): DefNode[] =>
  def.nodes.filter((n) => n.id !== selfId && n.type !== 'StartNode' && n.type !== 'EventSourceNode')

export const hasEventSource = (def: Def) => def.nodes.some((n) => n.type === 'EventSourceNode')
export const entryEventName = (def: Def): string => {
  const src = def.nodes.find((n) => n.type === 'EventSourceNode')
  const e = src?.config.event_name
  return typeof e === 'string' ? e.trim() : ''
}

export function summaryOf(n: DefNode): string {
  const c = n.config
  const s = (v: unknown, empty: string) => (typeof v === 'string' && v.trim() ? v : empty)
  switch (n.type) {
    case 'EventSourceNode':
      return `事件 ${s(c.event_name, '尚未設定')}`
    case 'SendMessageNode':
      return `範本 ${s(c.template_id, '尚未填')}`
    case 'WaitDelayNode':
      return `等 ${durationText(c.duration)}`
    case 'WaitForEventNode':
      return c.timeout ? `事件 ${s(c.event_name, '尚未設定')}，最多 ${durationText(c.timeout)}` : `事件 ${s(c.event_name, '尚未設定')}，不設時限`
    case 'ConditionNode': {
      const k = countLeaves(condOf(n))
      return k ? `${k} 個條件` : '尚未設定條件'
    }
    case 'SplitTestNode':
      return `${variantsOf(n).length} 組`
    case 'MergeNode':
      return '兩條路在此會合'
    case 'GoalNode':
      return s(c.goal_id, '尚未設定目標')
    case 'ExitNode':
      return s(c.reason, '尚未填原因')
    default:
      return ''
  }
}
function countLeaves(c: Cond): number {
  return isGroup(c) ? c.children.reduce((n, x) => n + countLeaves(x), 0) : 1
}

// ---------- 排版 ----------
/** 以 dagre 由左到右排版；只回傳 layout 缺少的節點座標，已有的原樣保留 */
export function fillLayout(def: Def, layout: Layout | null): Layout {
  const have = layout?.nodes ?? {}
  const nodes: Layout['nodes'] = {}
  const missing = def.nodes.filter((n) => !have[n.id])
  if (missing.length > 0) {
    const g = new Graph()
    g.setGraph({ rankdir: 'LR', nodesep: GAP_Y, ranksep: GAP_X, marginx: 0, marginy: 0, acyclicer: 'greedy' })
    g.setDefaultEdgeLabel(() => ({}))
    const ids = new Set(def.nodes.map((n) => n.id))
    for (const n of def.nodes) g.setNode(n.id, { width: CARD_W, height: CARD_H })
    for (const n of def.nodes) for (const t of targetsOf(n)) if (ids.has(t.target)) g.setEdge(n.id, t.target)
    dagreLayout(g)
    for (const n of missing) {
      const p = g.node(n.id)
      nodes[n.id] = { x: Math.round(p.x - CARD_W / 2), y: Math.round(p.y - CARD_H / 2) }
    }
  }
  for (const n of def.nodes) if (have[n.id]) nodes[n.id] = have[n.id]
  return { nodes }
}

/** 新節點放在錨點右側，被占用就往下推 */
export function placeNew(layout: Layout, anchorId: string | null, existing: string[]): { x: number; y: number } {
  const anchor = (anchorId && layout.nodes[anchorId]) || existing.map((id) => layout.nodes[id]).filter(Boolean).at(-1)
  let x = anchor ? anchor.x + CARD_W + GAP_X : 0
  const y0 = anchor ? anchor.y : 0
  let y = y0
  const taken = (px: number, py: number) =>
    existing.some((id) => {
      const p = layout.nodes[id]
      return p && Math.abs(p.x - px) < CARD_W + 8 && Math.abs(p.y - py) < CARD_H + 8
    })
  for (let i = 0; taken(x, y) && i < 200; i++) y += CARD_H + GAP_Y
  if (!Number.isFinite(x)) x = 0
  return { x, y }
}

// ---------- 迴圈（D-050：不限圈數，只提醒） ----------
export interface LoopInfo {
  /** 回邊：from 會回到 to */
  from: string
  to: string
}
/** 從入口深度優先找出回邊；沒有迴圈回傳空陣列 */
export function findLoops(def: Def): LoopInfo[] {
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  const state = new Map<string, 1 | 2>()
  const loops: LoopInfo[] = []
  const visit = (id: string) => {
    state.set(id, 1)
    const node = byId.get(id)
    if (node) {
      for (const t of targetsOf(node)) {
        if (!byId.has(t.target)) continue
        const s = state.get(t.target)
        if (s === 1) loops.push({ from: id, to: t.target })
        else if (!s) visit(t.target)
      }
    }
    state.set(id, 2)
  }
  const start = byId.has(def.entry_node) ? def.entry_node : def.nodes[0]?.id
  if (start) visit(start)
  for (const n of def.nodes) if (!state.has(n.id)) visit(n.id)
  const order = new Map(def.nodes.map((n, i) => [n.id, i]))
  return loops.sort((a, b) => (order.get(a.from) ?? 0) - (order.get(b.from) ?? 0))
}

/** 兩個以上的節點用文字串成清單項目（讀屏用） */
export function textOutline(def: Def): string[] {
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  const name = (id: string) => (byId.has(id) ? displayName(byId.get(id)!) : id)
  return def.nodes.map((n) => {
    const outs = outputSpecs(n)
      .map((o) => ({ o, t: targetOfPort(n, o.port) }))
      .filter((x) => x.t)
    const head = displayName(n)
    if (outs.length === 0) return `${head}（${n.type === 'EndNode' || n.type === 'GoalNode' || n.type === 'ExitNode' ? '出口' : '尚未連線'}）`
    return `${head} → ${outs.map(({ o, t }) => (outs.length > 1 ? `${o.label}：${name(t)}` : name(t))).join('；')}`
  })
}

/** 取代舊值但保持其他鍵 */
export function withSettings(def: Def, fn: (s: Record<string, unknown>) => Record<string, unknown>): Def {
  return { ...def, settings: fn({ ...def.settings }) }
}

export const nodeById = (def: Def, id: string | null): DefNode | undefined =>
  id ? def.nodes.find((n) => n.id === id) : undefined
