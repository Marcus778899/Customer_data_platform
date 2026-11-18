// 事件篩選（event_filter）的純邏輯：編輯用的樹、與契約（journeys.md §4）的互轉、白話摘要、驗證錯誤路徑對應。
// 這個檔不 import model.ts（model.ts 要用這裡的摘要函式）。

export const MAX_DEPTH = 5
export const FIELD_PREFIX = 'properties.'
export const KEY_RE = /^[a-z][a-z0-9_]*$/
const FIELD_RE = /^properties\.[a-z][a-z0-9_]*$/

export interface WireLeaf {
  type: 'attribute'
  field: string
  operator: string
  value?: unknown
}
export interface WireGroup {
  op: 'AND' | 'OR' | 'NOT'
  children: unknown[]
}

export interface FLeaf {
  kind: 'leaf'
  id: string
  /** 清單選的欄位完整路徑（properties.<鍵>）；手動輸入時不用 */
  field: string
  /** null = 用清單；字串 = 手動輸入中（只含鍵，前綴固定） */
  customKey: string | null
  operator: string
  text: string
  /** 數字欄：EQ／NEQ／IN／NOT_IN 的值也送 number */
  num: boolean
  /** 載入時的原樣；沒動過就原樣寫回，避免悄悄改掉伺服器上的內容 */
  raw?: WireLeaf
}
export interface FUnsup {
  kind: 'unsup'
  id: string
  raw: unknown
}
export interface FGroup {
  kind: 'group'
  id: string
  op: 'AND' | 'OR' | 'NOT'
  children: FNode[]
}
export type FNode = FLeaf | FUnsup | FGroup

let seq = 0
export const newFid = () => `f${++seq}`

// ---------- 欄位與比較 ----------
const KEY_LABEL: Record<string, string> = {
  cart_total: '購物車金額',
  order_total: '訂單金額',
  item_count: '商品數量',
  coupon_code: '優惠碼',
  utm_source: '來源管道',
  payment_method: '付款方式',
}
const keyOf = (field: string) => (field.startsWith(FIELD_PREFIX) ? field.slice(FIELD_PREFIX.length) : field)
export const keyLabel = (key: string) => KEY_LABEL[key] ?? key
export const filterFieldLabel = (field: string) => keyLabel(keyOf(field))

/**
 * 內建欄位型別對照（F-33）：只列確定的欄位；沒列的（手動輸入、建議清單裡型別不明的鍵，例 amount）一律「型別不明」，
 * 比較全列、由使用者用「比大小」分組決定存成數字還是文字。契約不變，送出的 JSON 型別依 value（契約 §4「比較語意」）。
 */
const NUMERIC_KEYS = new Set(['cart_total', 'order_total', 'item_count', 'price', 'quantity'])
const TEXT_KEYS = new Set(['utm_source', 'payment_method'])
export type FieldKind = 'num' | 'text' | 'unknown'
/** 欄位代碼 → 型別（接受 properties.<鍵>、trigger.properties.<鍵>、或單純的鍵） */
export function fieldKind(field: string): FieldKind {
  const k = keyOf(field.startsWith('trigger.') ? field.slice('trigger.'.length) : field)
  if (NUMERIC_KEYS.has(k) || /(_total|_amount|_count|_price|_qty|_quantity)$/.test(k)) return 'num'
  return TEXT_KEYS.has(k) ? 'text' : 'unknown'
}
export const isNumericField = (field: string) => fieldKind(field) === 'num'

export const FILTER_OPS: { value: string; label: string; noValue?: boolean }[] = [
  { value: 'EQ', label: '等於' },
  { value: 'NEQ', label: '不等於' },
  { value: 'GT', label: '大於' },
  { value: 'GTE', label: '至少' },
  { value: 'LT', label: '小於' },
  { value: 'LTE', label: '至多' },
  { value: 'CONTAINS', label: '包含' },
  { value: 'IN', label: '是其中之一' },
  { value: 'NOT_IN', label: '不是其中任何一個' },
  { value: 'IS_NOT_NULL', label: '有填', noValue: true },
  { value: 'IS_NULL', label: '沒填', noValue: true },
]
const NUM_OPS = ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NEQ', 'IS_NOT_NULL', 'IS_NULL']
const TEXT_OPS = ['EQ', 'NEQ', 'CONTAINS', 'IN', 'NOT_IN', 'IS_NOT_NULL', 'IS_NULL']
const COMPARE_OPS = ['GT', 'GTE', 'LT', 'LTE']
const LIST_OPS = ['IN', 'NOT_IN']
const opMeta = (op: string) => FILTER_OPS.find((o) => o.value === op)
export const opNoValue = (op: string) => !!opMeta(op)?.noValue
export const opLabel = (op: string) => opMeta(op)?.label ?? op

/** 比較的分組（型別不明的欄位用 optgroup 分三組；「比大小」會把值存成數字） */
export const OP_GROUPS: { label: string; ops: string[] }[] = [
  { label: '比大小（值會存成數字）', ops: COMPARE_OPS },
  { label: '比對內容', ops: ['EQ', 'NEQ', 'CONTAINS', 'IN', 'NOT_IN'] },
  { label: '有沒有填', ops: ['IS_NOT_NULL', 'IS_NULL'] },
]

/** 該欄位適用的比較；型別不明（含沒選欄位）列全部，已選的比較一定留著（載入的舊資料） */
export function opsFor(field: string, current: string) {
  const kind = field ? fieldKind(field) : 'unknown'
  const known = kind === 'unknown' ? FILTER_OPS.map((o) => o.value) : kind === 'num' ? NUM_OPS : TEXT_OPS
  const keep = known.includes(current) || !current ? known : [...known, current]
  return FILTER_OPS.filter((o) => keep.includes(o.value))
}

/** 型別不明的欄位要把比較分三組；已知型別用平的清單 */
export const groupedOps = (field: string) => !field || fieldKind(field) === 'unknown'

/** 「值當成：文字｜數字」只在型別不明欄位的 EQ／NEQ／IN／NOT_IN 出現 */
export const needsKindToggle = (field: string, operator: string) =>
  !!field && fieldKind(field) === 'unknown' && (operator === 'EQ' || operator === 'NEQ' || LIST_OPS.includes(operator))

/** 值欄用數字鍵盤：比大小，或值當成數字 */
export const numericInput = (operator: string, num: boolean) => COMPARE_OPS.includes(operator) || (num && (operator === 'EQ' || operator === 'NEQ' || LIST_OPS.includes(operator)))

/** 值的型別標示（進階資訊用） */
export const valueKindLabel = (operator: string, num: boolean) =>
  opNoValue(operator) ? '' : COMPARE_OPS.includes(operator) || (num && operator !== 'CONTAINS') ? '（數字）' : '（文字）'

export const leafField = (l: FLeaf) => (l.customKey !== null ? (l.customKey ? `${FIELD_PREFIX}${l.customKey}` : '') : l.field)

export const valueText = (v: unknown): string => (Array.isArray(v) ? v.join(',') : v === undefined || v === null ? '' : String(v))

type Parsed = { ok: true; value?: unknown } | { ok: false; error?: string }

/** 只接受 -?\d+(\.\d+)?，最多 15 位數字（不含單位、千分位、科學記號、全形、空白） */
const NUMBER_RE = /^-?\d+(\.\d+)?$/
const asNumber = (s: string) => (NUMBER_RE.test(s) && s.replace(/\D/g, '').length <= 15 ? Number(s) : null)
const numberError = (op: string) => `「${opLabel(op)}」要填數字，例如 500。不要加單位，1,000 請寫 1000。`

/** 文字轉成要送的 JSON 值；沒填完回 { ok:false }，格式錯另帶 error */
export function parseValue(l: Pick<FLeaf, 'operator' | 'text' | 'num'>): Parsed {
  const op = l.operator
  if (!op) return { ok: false }
  if (opNoValue(op)) return { ok: true }
  const t = l.text.trim()
  if (!t) return { ok: false }
  if (COMPARE_OPS.includes(op)) {
    const n = asNumber(t)
    return n === null ? { ok: false, error: numberError(op) } : { ok: true, value: n }
  }
  if (LIST_OPS.includes(op)) {
    const items = t.split(',').map((s) => s.trim()).filter(Boolean)
    if (!items.length) return { ok: false }
    if (!l.num) return { ok: true, value: items }
    const nums = items.map(asNumber)
    const bad = nums.findIndex((n) => n === null)
    return bad < 0 ? { ok: true, value: nums } : { ok: false, error: `第 ${bad + 1} 項『${items[bad]}』不是數字。多個值用逗號分隔，1,000 請寫 1000。` }
  }
  if (op === 'EQ' || op === 'NEQ') {
    if (!l.num) return { ok: true, value: t }
    const n = asNumber(t)
    return n === null ? { ok: false, error: numberError(op) } : { ok: true, value: n }
  }
  return { ok: true, value: t }
}

/** 值當成文字、但看起來像數字時的說明（不是錯誤，不擋存檔）；沒有要提示回 null */
export function textLooksNumber(l: Pick<FLeaf, 'operator' | 'text' | 'num'>, field: string): string | null {
  if (l.num || !needsKindToggle(field, l.operator)) return null
  const t = l.text.trim()
  const items = LIST_OPS.includes(l.operator) ? t.split(',').map((s) => s.trim()).filter(Boolean) : [t]
  const nums = items.filter((s) => asNumber(s) !== null)
  if (!nums.length) return null
  return `會當成文字 ${nums.map((s) => `"${s}"`).join('、')} 比對，事件送來的若是數字請改選數字，否則不會符合。`
}

export function leafWire(l: FLeaf): WireLeaf | null {
  if (l.raw) return l.raw
  const field = leafField(l)
  if (!FIELD_RE.test(field)) return null
  const p = parseValue(l)
  if (!p.ok) return null
  return p.value === undefined ? { type: 'attribute', field, operator: l.operator } : { type: 'attribute', field, operator: l.operator, value: p.value }
}

export const KEY_FORMAT_ERROR = '格式不符：只能用小寫英文、數字與底線，且要以英文字母開頭。'

/** 即時可判斷的行內錯誤（沒填完不算錯誤，只是「尚未完成」） */
export function leafInlineErrors(l: FLeaf): { field?: string; value?: string } {
  if (l.raw) return {}
  const out: { field?: string; value?: string } = {}
  if (l.customKey && !KEY_RE.test(l.customKey)) out.field = KEY_FORMAT_ERROR
  const p = parseValue(l)
  if (!p.ok && p.error) out.value = p.error
  return out
}

// ---------- 樹與契約互轉 ----------
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const isWireGroup = (v: unknown): v is WireGroup => isObj(v) && ['AND', 'OR', 'NOT'].includes(v.op as string) && Array.isArray(v.children)

function nodeFromWire(w: unknown): FNode {
  if (isWireGroup(w)) return { kind: 'group', id: newFid(), op: w.op, children: w.children.map(nodeFromWire) }
  if (isObj(w) && w.type === 'attribute' && typeof w.field === 'string' && typeof w.operator === 'string') {
    const v = w.value
    const num = typeof v === 'number' || (Array.isArray(v) && v.length > 0 && v.every((x) => typeof x === 'number')) || isNumericField(w.field)
    return { kind: 'leaf', id: newFid(), field: w.field, customKey: null, operator: w.operator, text: valueText(v), num, raw: w as unknown as WireLeaf }
  }
  return { kind: 'unsup', id: newFid(), raw: w }
}

export function treeFromWire(filter: unknown): FGroup {
  if (isWireGroup(filter)) return { kind: 'group', id: newFid(), op: filter.op, children: filter.children.map(nodeFromWire) }
  return { kind: 'group', id: newFid(), op: 'AND', children: filter ? [nodeFromWire(filter)] : [] }
}

export const emptyLeaf = (): FLeaf => ({ kind: 'leaf', id: newFid(), field: '', customKey: null, operator: '', text: '', num: false })
export const emptyGroup = (): FGroup => ({ kind: 'group', id: newFid(), op: 'AND', children: [] })

const included = (n: FNode): boolean => (n.kind === 'unsup' ? true : n.kind === 'leaf' ? leafWire(n) !== null : n.children.some(included))

/** 寫進草稿的樣子（沒填完的條件與空群組不寫）與每個節點在其中的路徑（event_filter.children[0]…） */
export function toWire(root: FGroup): { filter: WireGroup | null; paths: Map<string, string> } {
  const paths = new Map<string, string>([[root.id, 'event_filter']])
  const walk = (g: FGroup, path: string): WireGroup => {
    const children: unknown[] = []
    for (const c of g.children) {
      if (!included(c)) continue
      const p = `${path}.children[${children.length}]`
      paths.set(c.id, p)
      children.push(c.kind === 'group' ? walk(c, p) : c.kind === 'unsup' ? c.raw : leafWire(c))
    }
    return { op: g.op, children }
  }
  const w = walk(root, 'event_filter')
  return { filter: w.children.length ? w : null, paths }
}

export const wireKey = (v: unknown) => JSON.stringify(v ?? null)

export function countLeaves(n: FNode): number {
  return n.kind === 'group' ? n.children.reduce((s, c) => s + countLeaves(c), 0) : 1
}
export function countIncomplete(n: FNode): number {
  if (n.kind === 'group') return n.children.reduce((s, c) => s + countIncomplete(c), 0)
  return n.kind === 'leaf' && leafWire(n) === null ? 1 : 0
}
export const depthOf = (g: FGroup): number => 1 + Math.max(0, ...g.children.map((c) => (c.kind === 'group' ? depthOf(c) : 0)))

/** 不可變更新：以 id 找到節點，fn 回傳 null 代表移除 */
export function mapTree(g: FGroup, id: string, fn: (n: FNode) => FNode | null): FGroup {
  const children: FNode[] = []
  for (const c of g.children) {
    if (c.id === id) {
      const r = fn(c)
      if (r) children.push(r)
    } else children.push(c.kind === 'group' ? mapTree(c, id, fn) : c)
  }
  return { ...g, children }
}

// ---------- 白話句 ----------
function sentenceOfWire(w: unknown, nested = false): string {
  if (isWireGroup(w)) {
    const parts = w.children.map((c) => sentenceOfWire(c, true))
    if (w.op === 'NOT') return `不是（${parts.join('、')}）`
    const s = parts.join(w.op === 'AND' ? ' 且 ' : ' 或 ')
    return nested && parts.length > 1 ? `（${s}）` : s
  }
  if (isObj(w) && w.type === 'attribute' && typeof w.field === 'string') {
    const op = String(w.operator)
    const name = `「${filterFieldLabel(w.field)}」`
    if (opNoValue(op)) return `${name}${opLabel(op)}`
    const v = Array.isArray(w.value) ? w.value.join('、') : valueText(w.value)
    return `${name}${opLabel(op)} ${v}`
  }
  return '（畫面無法編輯的條件）'
}
export const filterSentence = (filter: unknown) => sentenceOfWire(filter)

export function filterLeafCount(filter: unknown): number {
  if (isWireGroup(filter)) return filter.children.reduce<number>((s, c) => s + filterLeafCount(c), 0)
  return filter ? 1 : 0
}

/** 節點卡第三行用：沒有篩選回 null（維持原摘要） */
export function cardFilterSummary(kind: 'source' | 'wait', filter: unknown, lead = ''): string | null {
  const k = filterLeafCount(filter)
  if (k === 0) return null
  if (kind === 'wait') return `${lead}・${k} 項條件`
  if (k === 1) {
    const g = filter as WireGroup
    return `只限${filterSentence(isWireGroup(g) && g.children.length === 1 ? g.children[0] : filter)}`
  }
  return `符合 ${k} 項條件`
}

/** 面板上方的一句話（aria-live）。event 是事件名稱，未填時用提示字 */
export function blockSummary(opts: { kind: 'source' | 'wait'; event: string; unlimited: boolean; filter: WireGroup | null; incomplete: number }): {
  text: string
  tone: 'ok' | 'todo'
} {
  const ev = `「${opts.event || '尚未填事件名稱'}」`
  const tail = opts.unlimited ? '其他事件不算等到，繼續等下去。' : '其他事件不算等到，繼續等到逾時。'
  const pending = opts.incomplete > 0 ? `另有 ${opts.incomplete} 項條件尚未完成，還不會寫入草稿。` : ''
  if (!opts.filter) {
    const base = opts.kind === 'source' ? `沒有篩選時，所有 ${ev} 事件都會讓客戶進入這個旅程。` : `沒有篩選時，所有 ${ev} 事件都算等到。`
    return { text: opts.incomplete > 0 ? `條件尚未完成，還不會寫入草稿。${base}` : base, tone: 'todo' }
  }
  const cond = filterSentence(opts.filter)
  const main = opts.kind === 'source' ? `只有 ${cond} 的 ${ev} 事件，才會讓客戶進入這個旅程。` : `只有 ${cond} 的 ${ev} 事件才算等到；${tail}`
  return { text: `${main}${pending}`, tone: 'ok' }
}

// ---------- 驗證錯誤對應 ----------
export interface IssueLike {
  field: string
  message: string
}
export interface RowIssues {
  row: string[]
  field: string[]
  operator: string[]
  value: string[]
}

/** 取出屬於這個節點的篩選錯誤：path 相對於 config（例 event_filter.children[0].value） */
export function filterIssues(issues: readonly IssueLike[], nodeIndex: number): { path: string; message: string }[] {
  const head = `definition.nodes[${nodeIndex}].config.`
  return issues.filter((i) => i.field.startsWith(`${head}event_filter`)).map((i) => ({ path: i.field.slice(head.length), message: i.message }))
}

export function issuesAt(list: { path: string; message: string }[], path: string | undefined): RowIssues {
  const out: RowIssues = { row: [], field: [], operator: [], value: [] }
  if (!path) return out
  for (const i of list) {
    if (i.path === path || i.path === `${path}.type` || i.path === `${path}.op`) out.row.push(i.message)
    else if (i.path === `${path}.field`) out.field.push(i.message)
    else if (i.path === `${path}.operator`) out.operator.push(i.message)
    else if (i.path === `${path}.value`) out.value.push(i.message)
  }
  return out
}
