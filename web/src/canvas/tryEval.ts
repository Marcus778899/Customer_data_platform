// 條件「試試看」試算的純邏輯：判斷規則同契約 docs/contracts/journeys.md §4「比較語意」與 §4.1 測試向量（引擎 ConditionEvaluator 同一套）。
// 純前端：只用使用者輸入的樣本，不讀客戶、不呼叫 API。
import { COMPARE_OPS, opLabel, opNoValue } from './eventFilter'
import { condValueText, eventFieldLabel, fieldLabel, isGroup, type Cond, type CondGroup, type CondLeaf } from './model'

/** 可以判斷的葉節點（attribute）；behavior、segment 等不支援 */
export const isAttrLeaf = (c: unknown): c is CondLeaf =>
  typeof c === 'object' && c !== null && !('op' in c) && (c as { type?: unknown }).type === 'attribute'

/** 樹裡有沒有試算不支援的葉節點（behavior、segment、其他） */
export function hasUnsupported(c: unknown): boolean {
  if (typeof c !== 'object' || c === null) return true
  if ('op' in c) return ((c as CondGroup).children ?? []).some(hasUnsupported)
  return !isAttrLeaf(c)
}

// ---------- 判斷（§4 比較語意） ----------
const isMissing = (v: unknown) => v === undefined || v === null
const sameJson = (a: unknown, b: unknown): boolean => {
  if (typeof a !== typeof b) return false
  return typeof a === 'object' ? JSON.stringify(a) === JSON.stringify(b) : a === b
}

/** actual = undefined 或 null 代表欄位不存在 */
export function evalLeaf(actual: unknown, operator: string, value: unknown): boolean {
  if (operator === 'IS_NULL') return isMissing(actual)
  if (isMissing(actual)) return false
  switch (operator) {
    case 'IS_NOT_NULL':
      return true
    case 'GT':
    case 'GTE':
    case 'LT':
    case 'LTE': {
      if (typeof actual !== 'number' || typeof value !== 'number') return false
      return operator === 'GT' ? actual > value : operator === 'GTE' ? actual >= value : operator === 'LT' ? actual < value : actual <= value
    }
    case 'EQ':
      return sameJson(actual, value)
    case 'NEQ':
      return !sameJson(actual, value)
    case 'IN':
      return Array.isArray(value) && value.some((x) => sameJson(actual, x))
    case 'NOT_IN':
      return Array.isArray(value) && !value.some((x) => sameJson(actual, x))
    case 'CONTAINS':
      if (typeof actual === 'string') return typeof value === 'string' && actual.includes(value)
      return Array.isArray(actual) && actual.some((x) => sameJson(x, value))
    default:
      return false
  }
}

/** 整棵條件樹；getActual 取每個葉節點的實際值 */
export function evalTree(c: Cond, getActual: (leaf: CondLeaf) => unknown): boolean {
  if (isGroup(c)) {
    // 同引擎 ConditionEvaluator：空群組不成立；NOT 恰好一個子條件才可能成立
    if (c.children.length === 0) return false
    if (c.op === 'NOT') return c.children.length === 1 && !evalTree(c.children[0], getActual)
    return c.op === 'OR' ? c.children.some((x) => evalTree(x, getActual)) : c.children.every((x) => evalTree(x, getActual))
  }
  return evalLeaf(getActual(c), c.operator, c.value)
}

// ---------- 樣本轉型 ----------
const NUMBER_RE = /^-?\d+(\.\d+)?$/
/** 樣本文字 → JSON 值：空白 = 欄位不存在；要當數字時數字樣子的文字轉 number，否則字串 */
export function parseSample(text: string, asNumber: boolean): unknown {
  const t = text.trim()
  if (!t) return undefined
  if (asNumber && NUMBER_RE.test(t)) return Number(t)
  return text
}
const numberish = (v: unknown) => typeof v === 'number' || (Array.isArray(v) && v.length > 0 && v.every((x) => typeof x === 'number'))

/** 欄位白話名：常見欄位用對照；事件屬性（trigger.properties.<鍵>）寫「事件屬性 <鍵>」，與欄位下拉一致 */
export const nameOf = (field: string): string => {
  const named = fieldLabel(field)
  return named === field && field.startsWith('trigger.properties.') ? eventFieldLabel(field.slice('trigger.properties.'.length)) : named
}

// ---------- 整理條件 ----------
/** 去掉還沒選欄位的葉節點與空群組（試算只看填完的條件；草稿本身照原樣寫入）；全空回 null */
export function prune(c: Cond): Cond | null {
  if (isGroup(c)) {
    const children = c.children.map(prune).filter((x): x is Cond => x !== null)
    return children.length ? { ...c, children } : null
  }
  return isAttrLeaf(c) && c.field ? c : null
}

export function leavesOf(c: Cond): CondLeaf[] {
  return isGroup(c) ? c.children.flatMap(leavesOf) : [c]
}

/** 每個欄位一個樣本輸入；值是 number（或全數字陣列）的條件，該欄位的樣本當數字 */
export function sampleFields(leaves: CondLeaf[]): { field: string; name: string; asNumber: boolean }[] {
  const out = new Map<string, { field: string; name: string; asNumber: boolean }>()
  for (const l of leaves) {
    const cur = out.get(l.field)
    out.set(l.field, { field: l.field, name: nameOf(l.field), asNumber: (cur?.asNumber ?? false) || numberish(l.value) })
  }
  return [...out.values()]
}

// ---------- 句子 ----------
const valueShown = (v: unknown) => (Array.isArray(v) ? v.join('、') : condValueText(v))

/** 一個條件的白話片段：「大於 500」「有填」；值還沒填用「…」 */
export function fragment(l: CondLeaf): string {
  if (opNoValue(l.operator)) return opLabel(l.operator)
  return `${opLabel(l.operator)} ${valueShown(l.value) || '…'}`
}

const LEAD: Record<CondGroup['op'], string> = { AND: '全部符合', OR: '任一符合', NOT: '都不符合' }

/** 面板頂端的句子：「客戶要全部符合：『購物車金額』大於 500；『會員等級』等於 gold。」 */
export function conditionSentence(root: Cond): string {
  const part = (c: Cond, nested: boolean): string => {
    if (!isGroup(c)) return `『${nameOf(c.field)}』${fragment(c)}`
    const body = c.children.map((x) => part(x, true)).join('；')
    return nested ? `（${LEAD[c.op]}：${body}）` : body
  }
  const p = prune(root)
  if (!p) return '還沒有可判斷的條件。'
  const lead = isGroup(p) ? LEAD[p.op] : LEAD.AND
  return `客戶要${lead}：${part(p, false)}。`
}

// ---------- 範例客戶 ----------
export interface ExampleValue {
  /** undefined = 沒填 */
  value: unknown
  shown: string
  note: string
  /** 這一條不合 */
  bad?: boolean
}
const TIERS = ['silver', 'gold', 'bronze']
const altText = (v: unknown) => TIERS.find((t) => t !== String(v).trim()) ?? '其他值'
const OUTSIDE = '清單以外的值'

/** 讓這個條件成立（ok）或不成立的樣本值（附說明）；比大小時剛好等於的邊界會寫「剛好等於不算／也算」 */
export function exampleFor(l: CondLeaf, ok: boolean): ExampleValue {
  const v = l.value
  const out = (value: unknown, note = ''): ExampleValue => ({ value, shown: value === undefined ? '沒填' : String(value), note })
  const op = l.operator
  if (COMPARE_OPS.includes(op)) {
    if (typeof v !== 'number') return { value: undefined, shown: '—', note: '請先填數字' }
    const step = Math.max(1, Math.round(Math.abs(v) * 0.2))
    const by: Record<string, [ExampleValue, ExampleValue]> = {
      GT: [out(v + step), out(v, '剛好等於不算')],
      GTE: [out(v, '剛好等於也算'), out(v - step)],
      LT: [out(v - step), out(v, '剛好等於不算')],
      LTE: [out(v, '剛好等於也算'), out(v + step)],
    }
    return by[op][ok ? 0 : 1]
  }
  const first = Array.isArray(v) ? v[0] : v
  const other = typeof first === 'number' ? first + 1 : altText(first)
  switch (op) {
    case 'EQ':
      return out(ok ? first : other)
    case 'NEQ':
      return out(ok ? other : first)
    case 'CONTAINS':
      return out(ok ? `${condValueText(v)}_plus` : other)
    case 'IN':
      return out(ok ? first : OUTSIDE)
    case 'NOT_IN':
      return out(ok ? OUTSIDE : first)
    case 'IS_NOT_NULL':
      return ok ? out('gold') : out(undefined)
    case 'IS_NULL':
      return ok ? out(undefined) : out('gold')
    default:
      return out(undefined)
  }
}

/** 範例客戶的一個欄位：每個欄位一個值（引擎也是每個欄位取一個值） */
export interface ExampleRow {
  field: string
  name: string
  /** undefined = 沒填 */
  value: unknown
  shown: string
  /** 補充說明（例：剛好等於也算） */
  notes: string[]
  /** 這個欄位不合的原因（例：要大於 500（剛好等於不算）） */
  bads: string[]
}
export interface Examples {
  /** null = 沒有客戶能符合（條件互相矛盾） */
  yes: ExampleRow[] | null
  /** null = 每位客戶都會符合 */
  no: ExampleRow[] | null
}

const boundary = (l: CondLeaf, v: unknown): string => {
  if (typeof v !== 'number' || v !== l.value) return ''
  return l.operator === 'GT' || l.operator === 'LT' ? '剛好等於不算' : l.operator === 'GTE' || l.operator === 'LTE' ? '剛好等於也算' : ''
}
const MAX_COMBOS = 20000

/** 巢狀的 NOT 不是恰好一個子條件時，引擎不會讓它成立 */
export function hasBadNot(c: Cond): boolean {
  return isGroup(c) && ((c.op === 'NOT' && c.children.length !== 1) || c.children.some(hasBadNot))
}

/**
 * 一位會符合、一位不會符合的客戶：每個欄位一個值，候選值取該欄位各條件的範例值（成立、不成立、沒填），
 * 用 evalTree 搜尋組合；不會符合的那位盡量只讓一個欄位跟會符合那位不同。
 * 找不到會符合（條件互斥）或不會符合（永遠成立）的客戶時該側回 null。條件太多組合爆炸時整個回 null。
 */
export function buildExamples(root: Cond): Examples | null {
  const leaves = leavesOf(root)
  const fields = sampleFields(leaves)
  if (!fields.length) return null
  const cands = fields.map((f) => {
    const seen = new Set<string>()
    const out: unknown[] = []
    const add = (v: unknown) => {
      const k = v === undefined ? 'undef' : JSON.stringify(v)
      if (!seen.has(k)) {
        seen.add(k)
        out.push(v)
      }
    }
    const mine = leaves.filter((l) => l.field === f.field)
    mine.forEach((l) => add(exampleFor(l, true).value))
    mine.forEach((l) => add(exampleFor(l, false).value))
    add(undefined)
    return out
  })
  const total = cands.reduce((n, c) => n * c.length, 1)
  if (total > MAX_COMBOS) return null
  const combos: unknown[][] = []
  const walk = (i: number, cur: unknown[]) => {
    if (i === cands.length) return void combos.push(cur)
    for (const v of cands[i]) walk(i + 1, [...cur, v])
  }
  walk(0, [])
  const passOf = (vals: unknown[]) => evalTree(root, (l) => vals[fields.findIndex((f) => f.field === l.field)])
  const results = combos.map((vals) => ({ vals, pass: passOf(vals) }))
  const yes = results.find((r) => r.pass)
  const diff = (a: unknown[], b: unknown[]) => a.filter((v, i) => JSON.stringify(v) !== JSON.stringify(b[i])).length
  const noList = results.filter((r) => !r.pass)
  const no = yes ? noList.reduce<(typeof noList)[number] | undefined>((best, r) => (!best || diff(r.vals, yes.vals) < diff(best.vals, yes.vals) ? r : best), undefined) : noList[0]
  const rows = (vals: unknown[], base: unknown[] | null): ExampleRow[] =>
    fields.map((f, i) => {
      const mine = leaves.filter((l) => l.field === f.field)
      const v = vals[i]
      const changed = base !== null && JSON.stringify(v) !== JSON.stringify(base[i])
      const fails = mine.filter((l) => !evalLeaf(v, l.operator, l.value))
      return {
        field: f.field,
        name: f.name,
        value: v,
        shown: v === undefined ? '沒填' : String(v),
        notes: base === null ? mine.map((l) => boundary(l, v)).filter(Boolean) : [],
        bads: changed
          ? fails.map((l) => {
              const b = boundary(l, v)
              return `不合：要${fragment(l)}${b ? `（${b}）` : ''}`
            })
          : [],
      }
    })
  return { yes: yes ? rows(yes.vals, null) : null, no: no ? rows(no.vals, yes ? yes.vals : null) : null }
}
