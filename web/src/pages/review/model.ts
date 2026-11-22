// 審核畫面的純邏輯：按鈕可見性（rv-matrix）、錯誤提示、等待時間、節點比對、紀錄列
import { ApiError } from '../../api/client'
import type { CurrentUser, DraftDefinition, JourneyDetail, JourneyNode, Review } from '../../api/types'
import { displayName, summaryOf } from '../../canvas/model'

export type ActionKey = 'edit' | 'resubmit' | 'discard' | 'stop' | 'withdraw' | 'approve' | 'reject' | 'delete'

export interface ActionSpec {
  key: ActionKey
  label: string
  kind: 'primary' | 'secondary' | 'danger'
  /** 有值 = 停用（aria-disabled），值是原因 */
  disabled?: string
  /** true = 收在「⋯ 更多動作」選單裡（刪除旅程、停止旅程、捨棄草稿） */
  more?: boolean
}

export const VIEW_ONLY = '你的角色（檢視者）只能查看。'
export const NOT_PUBLISHED = '尚未發佈的旅程不能停止或刪除草稿。'
export const LOCKED = '草稿送審中，內容已鎖定。要修改或刪除，請先撤回送審。'
export const OWN_APPROVE = '不能核准自己送審的版本，請另一位管理員審核。'
export const NOT_OWNER = '只有送審者或管理員可以撤回。'
export const ADMIN_ONLY = '只有管理員可以處理審核。'

type Submitter = { submitted_by: { user_id: string } | null } | null | undefined

export const isMine = (review: Submitter, user: CurrentUser) =>
  !!review?.submitted_by && review.submitted_by.user_id === user.user_id

/** 撤回的停用原因；可撤回回傳 undefined（送審者本人或管理員） */
export const withdrawBlock = (review: Submitter, user: CurrentUser) =>
  user.role === 'ADMIN' || isMine(review, user) ? undefined : NOT_OWNER

/**
 * 旅程頁標頭的動作（rv-matrix、canvas-interaction.md §4）。
 * 因旅程狀態不適用的動作不出現（DRAFT 沒有停止、捨棄草稿；只有 DRAFT 才有刪除旅程）；
 * 只有角色或送審鎖定才停用，原因由 headerReason 合成標頭唯一一行。已停止沒有動作。
 */
export function journeyActions(detail: JourneyDetail, user: CurrentUser): ActionSpec[] {
  if (detail.status === 'ABORTED') return []
  const never = detail.status === 'DRAFT'
  if (user.role === 'VIEWER') {
    const out: ActionSpec[] = [{ key: 'edit', label: '編輯', kind: 'secondary', disabled: VIEW_ONLY }]
    if (never) out.push({ key: 'delete', label: '刪除旅程', kind: 'danger', disabled: VIEW_ONLY, more: true })
    else out.push({ key: 'stop', label: '停止旅程', kind: 'danger', disabled: VIEW_ONLY, more: true })
    return out
  }
  const stop: ActionSpec[] = never ? [] : [{ key: 'stop', label: '停止旅程', kind: 'danger', more: true }]
  const discard: ActionSpec[] = never ? [] : [{ key: 'discard', label: '捨棄草稿', kind: 'secondary', more: true }]
  const del = (disabled?: string): ActionSpec[] =>
    never ? [{ key: 'delete', label: '刪除旅程', kind: 'danger', disabled, more: true }] : []
  if (detail.review_status === 'PENDING') {
    const rv = detail.latest_review
    const out: ActionSpec[] = []
    if (user.role === 'ADMIN') {
      out.push({
        key: 'approve',
        label: '核准並發佈',
        kind: 'primary',
        disabled: isMine(rv, user) ? OWN_APPROVE : undefined,
      })
      out.push({ key: 'reject', label: '退回', kind: 'secondary' })
    }
    out.push({ key: 'withdraw', label: '撤回送審', kind: 'secondary', disabled: withdrawBlock(rv, user) })
    out.push({ key: 'edit', label: '編輯', kind: 'secondary', disabled: LOCKED })
    return [...out, ...stop, ...del(LOCKED)]
  }
  if (detail.review_status === 'REJECTED') {
    return [{ key: 'resubmit', label: '修改後再送審', kind: 'primary' }, ...discard, ...stop, ...del()]
  }
  const edit: ActionSpec = { key: 'edit', label: '編輯', kind: 'primary' }
  if (detail.has_draft) return [edit, ...discard, ...stop, ...del()]
  return [edit, ...stop, ...del()]
}

/** 標頭唯一一行停用原因：把停用動作的原因去重後合在一起；沒有停用動作回傳 null */
export function headerReason(specs: ActionSpec[]): string | null {
  const texts: string[] = []
  for (const s of specs) if (s.disabled && !texts.includes(s.disabled)) texts.push(s.disabled)
  return texts.length ? texts.join('') : null
}

/** 審核頁的兩顆鈕：非管理員停用；自己送的核准停用 */
export function reviewPageActions(review: Review, user: CurrentUser): ActionSpec[] {
  const admin = user.role === 'ADMIN'
  return [
    { key: 'reject', label: '退回', kind: 'secondary', disabled: admin ? undefined : ADMIN_ONLY },
    {
      key: 'approve',
      label: '核准並發佈',
      kind: 'primary',
      disabled: !admin ? ADMIN_ONLY : isMine(review, user) ? OWN_APPROVE : undefined,
    },
  ]
}

export interface Described {
  text: string
  /** 要提供「重新載入」 */
  reload: boolean
}

/** 審核、停止、捨棄失敗時的白話提示（契約 §3.2、§3.5 的錯誤碼） */
export function describeError(e: unknown): Described {
  const err = e instanceof ApiError ? e : new ApiError(0, null, null)
  const code = err.code
  if (err.status === 409 && code === 'REVIEW_NOT_PENDING')
    return { text: '這筆審核已被其他管理員處理，請重新載入。', reload: true }
  if (err.status === 404 && code === 'REVIEW_NOT_FOUND') return { text: '找不到這筆審核，請重新載入。', reload: true }
  if (err.status === 400 && code === 'VALIDATION_FAILED') {
    const n = err.issues.length
    return {
      text: `這個版本不能核准：核准前重新驗證${n ? `有 ${n} 個錯誤` : '失敗'}，這筆審核維持「審核中」。可能是送審後設定被其他變更影響；請退回給送審者修正。`,
      reload: false,
    }
  }
  if (err.status === 403 && code === 'SELF_APPROVAL_FORBIDDEN') return { text: OWN_APPROVE, reload: false }
  if (err.status === 403) return { text: '你的角色沒有權限執行這個操作。', reload: false }
  if (err.status === 409 && code === 'JOURNEY_ABORTED')
    return { text: '這個旅程已停止，不能再修改或發佈，請重新載入。', reload: true }
  if (err.status === 409 && code === 'DRAFT_IN_REVIEW')
    return { text: '這份草稿已送審，不能捨棄；請先撤回送審。', reload: true }
  if (err.status === 409 && code === 'JOURNEY_NOT_PUBLISHED') return { text: NOT_PUBLISHED, reload: false }
  return { text: '操作失敗，請稍後再試。', reload: false }
}

const HOUR = 3_600_000
const DAY = 24 * HOUR
/** 「等待 N 小時」「等待 N 天」：未滿 24 小時用小時，之後用天 */
export function waitText(submittedAt: string, now: Date = new Date()): string {
  const ms = now.getTime() - new Date(submittedAt).getTime()
  if (!Number.isFinite(ms) || ms < HOUR) return '等待不到 1 小時'
  if (ms < DAY) return `等待 ${Math.floor(ms / HOUR)} 小時`
  return `等待 ${Math.floor(ms / DAY)} 天`
}

// ---------- 節點比對（審核頁，依 id；outputs 不算） ----------
export type Change = 'added' | 'removed' | 'modified'
export interface NodeChange {
  id: string
  change: Change
  name: string
  before: string | null
  after: string | null
}

function stable(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(stable).join(',')}]`
  if (v && typeof v === 'object')
    return `{${Object.keys(v)
      .sort()
      .map((k) => `${JSON.stringify(k)}:${stable((v as Record<string, unknown>)[k])}`)
      .join(',')}}`
  return JSON.stringify(v) ?? 'null'
}

export function diffNodes(base: { nodes: JourneyNode[] } | null, draft: DraftDefinition): NodeChange[] {
  const before = new Map((base?.nodes ?? []).map((n) => [n.id, n]))
  const after = new Map(draft.nodes.map((n) => [n.id, n]))
  const out: NodeChange[] = []
  for (const n of draft.nodes) {
    const old = before.get(n.id)
    if (!old) out.push({ id: n.id, change: 'added', name: displayName(n), before: null, after: summaryOf(n) })
    else if ((old.name ?? null) !== (n.name ?? null) || stable(old.config) !== stable(n.config))
      out.push({
        id: n.id,
        change: 'modified',
        name: displayName(n),
        before: `${displayName(old)}・${summaryOf(old)}`,
        after: `${displayName(n)}・${summaryOf(n)}`,
      })
  }
  for (const n of base?.nodes ?? [])
    if (!after.has(n.id)) out.push({ id: n.id, change: 'removed', name: displayName(n), before: summaryOf(n), after: null })
  return out
}

export const CHANGE_LABEL: Record<Change, string> = { added: '新增', modified: '修改', removed: '刪除' }

// ---------- 審核紀錄列（契約 §3.2 末條） ----------
export interface HistoryRow {
  key: string
  who: string
  action: string
  at: string
  detail: string | null
}

const KEY_NAME = '管理金鑰'
const STOP_REASON = '旅程已停止'

/** 每筆 Review 一列；操作者 PENDING 取送審者、其餘取決定者（null = 管理金鑰） */
export function reviewRow(r: Review): HistoryRow {
  const base = r.base_version !== null ? `（基於 v${r.base_version}）` : ''
  const reason = (label: string) => (r.reason ? `${label}：${r.reason}` : null)
  const at = r.decided_at ?? r.submitted_at
  const who = r.decided_by?.display_name ?? KEY_NAME
  switch (r.status) {
    case 'PENDING':
      return {
        key: r.review_id,
        who: r.submitted_by?.display_name ?? KEY_NAME,
        action: `送審${base}`,
        at: r.submitted_at,
        detail: r.note ? `說明：${r.note}` : null,
      }
    case 'APPROVED': {
      const fromKey = r.submitted_by === null
      return {
        key: r.review_id,
        who,
        action: `${fromKey ? '建立' : '核准並發佈'} v${r.version ?? '?'}`,
        at,
        detail: reason(fromKey ? '原因' : '備註'),
      }
    }
    case 'REJECTED':
      return { key: r.review_id, who, action: `退回${base}`, at, detail: reason('原因') }
    case 'WITHDRAWN': {
      const byStop = r.reason === STOP_REASON
      return {
        key: r.review_id,
        who,
        action: byStop ? `自動撤回送審${base}（因旅程停止）` : `撤回送審${base}`,
        at,
        detail: byStop ? null : reason('原因'),
      }
    }
  }
}

/** 已停止的旅程併入一列「停止旅程」放在最上面（契約 §3.2） */
export function stopRow(d: JourneyDetail): HistoryRow | null {
  if (d.status !== 'ABORTED' || !d.stopped_at) return null
  return {
    key: 'stop',
    who: d.stopped_by?.display_name ?? KEY_NAME,
    action: '停止旅程',
    at: d.stopped_at,
    detail: d.stop_reason ? `原因：${d.stop_reason}` : null,
  }
}
