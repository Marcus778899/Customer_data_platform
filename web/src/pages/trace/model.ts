import type { Delivery, EnrollmentDetail, JourneyNode, JourneyVersion, TraceEntry } from '../../api/types'
import { formatDuration, formatPlainTime, TERMINAL } from '../../format'
import { channelText, isoDurationText, nodeTypeText, skipReasonText, stepTitle } from '../../plain'

/** 旅程定義的可用程度：ok = 可列「接下來」；其餘一律降級為只列已走過的步驟 */
export type DefState = 'ok' | 'loading' | 'failed' | 'mismatch'

export type StepKind = 'done' | 'now' | 'next' | 'err' | 'stopped'
export interface StepVM {
  key: string
  kind: StepKind
  label: string
  title: string
  icon: 'in' | 'wait' | 'send' | 'end' | 'other'
  desc: string | null
  time: string | null
}

export interface SummaryRow {
  q: string
  strong: string
  rest?: string
  hint?: string
}
export interface SummaryVM {
  tone: 'warning' | 'success' | 'danger' | 'neutral' | 'info'
  rows: [SummaryRow, SummaryRow, SummaryRow]
  diagnostic: boolean
}

const iconOf = (type: string): StepVM['icon'] =>
  type === 'EventSourceNode' ? 'in' : type === 'WaitDelayNode' ? 'wait' : type === 'SendMessageNode' ? 'send' : type === 'EndNode' ? 'end' : 'other'

const str = (v: unknown): string | null => (typeof v === 'string' ? v : null)

/** 白話時間（不附括號內的相對時間；D-079） */
export const plainAt = (iso: string, now: Date) => formatPlainTime(iso, now, { relative: false })

function entryDelay(t: TraceEntry, def: JourneyVersion | null): string | null {
  const node = def?.nodes.find((n) => n.id === t.node_id)
  return str(t.evaluated.duration) ?? str(node?.config.duration)
}

function entryTitle(t: TraceEntry, def: JourneyVersion | null): string | null {
  return stepTitle(t.node_type, t.node_name, t.node_type === 'WaitDelayNode' ? entryDelay(t, def) : null)
}

/** 目前停留的軌跡項目：未結束的最後一筆；沒有就取最後一筆 */
function currentEntry(d: EnrollmentDetail): TraceEntry | undefined {
  return [...d.trace].reverse().find((t) => t.exited_at === null) ?? d.trace[d.trace.length - 1]
}

/** 目前所在步驟的白話名稱 */
export function currentStepName(d: EnrollmentDetail, def: JourneyVersion | null): string {
  const t = currentEntry(d)
  const byTrace = t && t.node_id === d.current_node_id ? entryTitle(t, def) : null
  const node = def?.nodes.find((n) => n.id === d.current_node_id)
  const delay = d.current_node_type === 'WaitDelayNode' ? str(node?.config.duration) : null
  return byTrace ?? stepTitle(d.current_node_type, d.current_node_name, delay) ?? nodeTypeText(d.current_node_type)
}

const firstOut = (n: JourneyNode): string | undefined => {
  const o = n.outputs ?? {}
  return o.next ?? o.sent ?? Object.values(o)[0]
}

/** 從某節點之後沿著出口往下走（直線旅程；有迴圈就停） */
function chainAfter(def: JourneyVersion, fromId: string, includeFrom: boolean): JourneyNode[] {
  const byId = new Map(def.nodes.map((n) => [n.id, n]))
  const out: JourneyNode[] = []
  const seen = new Set<string>()
  let cur = byId.get(fromId)
  if (!cur) return out
  if (includeFrom) {
    out.push(cur)
    seen.add(cur.id)
  }
  for (;;) {
    const nextId = firstOut(cur)
    const next = nextId ? byId.get(nextId) : undefined
    if (!next || seen.has(next.id)) break
    seen.add(next.id)
    out.push(next)
    cur = next
  }
  return out
}

export interface Upcoming {
  key: string
  type: string
  title: string
}
/** 「接下來」的步驟：只在非終態、定義可用時有值 */
export function upcomingSteps(d: EnrollmentDetail, def: JourneyVersion | null): Upcoming[] {
  if (!def || TERMINAL.has(d.status)) return []
  const open = [...d.trace].reverse().find((t) => t.exited_at === null)
  const from = open?.node_id ?? d.current_node_id
  return chainAfter(def, from, !open)
    .map((n) => ({
      key: n.id,
      type: n.type,
      title: stepTitle(n.type, n.name, n.type === 'WaitDelayNode' ? str(n.config.duration) : null),
    }))
    .filter((u): u is Upcoming => u.title !== null)
}

const deliveriesOf = (d: EnrollmentDetail, seq: number): Delivery[] => d.delivery_history.filter((x) => x.trace_seq === seq)

function doneDesc(t: TraceEntry, d: EnrollmentDetail, def: JourneyVersion | null): string | null {
  switch (t.node_type) {
    case 'EventSourceNode':
      return t.node_name ? `客戶完成了「${t.node_name}」，因此進入這個旅程。` : '客戶符合進入條件，因此進入這個旅程。'
    case 'WaitDelayNode': {
      const delay = entryDelay(t, def)
      const text = delay ? isoDurationText(delay) : null
      return text && text !== delay ? `等了 ${text}，時間到了就自動繼續。` : '等待結束，已自動繼續。'
    }
    case 'SendMessageNode': {
      const ds = deliveriesOf(d, t.seq)
      if (ds.length === 0) return '這一步已處理完成。'
      const s = ds[0].status
      if (s === 'SENT') return '訊息已送出。'
      if (s === 'PENDING') return '訊息已排入發送，還沒有送達。'
      if (s === 'FAILED') return '訊息發送失敗。'
      if (s === 'CANCELLED') return '訊息已取消，沒有送出。'
      return `訊息沒有送出：${ds[0].skip_reason ? skipReasonText(ds[0].skip_reason) : '被略過'}。`
    }
    case 'EndNode':
      return '這位客戶已走完整個旅程。'
    default:
      return null
  }
}

/** 走過的步驟＋接下來的步驟（依 seq 由舊到新） */
export function buildSteps(d: EnrollmentDetail, def: JourneyVersion | null, now: Date): StepVM[] {
  const terminal = TERMINAL.has(d.status)
  const open = [...d.trace].reverse().find((t) => t.exited_at === null)
  // 終態時停下的那一步：未離開的最後一筆；沒有就最後一筆（FAILED、ABORTED 才標示）
  const stopAt = d.status === 'FAILED' || d.status === 'ABORTED' ? (open ?? d.trace[d.trace.length - 1]) : undefined
  const out: StepVM[] = []
  for (const t of d.trace) {
    const title = entryTitle(t, def)
    if (title === null) continue
    const base = { key: `t${t.seq}`, title, icon: iconOf(t.node_type) }
    const entered = plainAt(t.entered_at, now)
    if (t === stopAt && d.status === 'FAILED') {
      out.push({ ...base, kind: 'err', label: '發生問題', desc: '這一步發生錯誤，旅程停在這裡，不會自動繼續。', time: entered })
    } else if (t === stopAt) {
      const end = d.terminated_at ? ` → ${plainAt(d.terminated_at, now)}` : ''
      const desc = t.node_type === 'WaitDelayNode' ? '在等待中被中止。' : '在這一步被中止。'
      out.push({ ...base, kind: 'stopped', label: '已中止', desc, time: `${entered}${end}` })
    } else if (!terminal && t === open) {
      const wake = d.wake_condition?.wake_at
      if (t.node_type === 'WaitDelayNode') {
        const waited = formatDuration(now.getTime() - new Date(t.entered_at).getTime())
        out.push({
          ...base,
          kind: 'now',
          label: '目前在這裡',
          desc: `正在等待，已等 ${waited}。時間到了就會自動繼續，不需要任何人操作。`,
          time: `${entered} 開始${wake ? ` · 預計 ${plainAt(wake, now)} 繼續` : ''}`,
        })
      } else {
        out.push({ ...base, kind: 'now', label: '目前在這裡', desc: '這一步正在處理中，通常幾秒內會到下一步。', time: `${entered} 開始` })
      }
    } else {
      out.push({ ...base, kind: 'done', label: '已完成', desc: doneDesc(t, d, def), time: entered })
    }
  }
  const wake = d.wake_condition?.wake_at
  upcomingSteps(d, def).forEach((u, i) => {
    const desc = u.type === 'SendMessageNode' ? '等待結束後，會發送這則訊息給客戶。' : u.type === 'WaitDelayNode' ? '會先等待一段時間，時間到才繼續。' : null
    out.push({
      key: `u${u.key}`,
      kind: 'next',
      label: '接下來',
      title: u.title,
      icon: iconOf(u.type),
      desc,
      time: i === 0 && wake && d.status === 'WAITING' ? `預計 ${plainAt(wake, now)} 之後` : null,
    })
  })
  return out
}

/** 副標用的客戶姓名；姓名在 masked_paths 時照後端給的遮罩值顯示 */
export function customerName(d: EnrollmentDetail): { name: string | null; masked: boolean } {
  const snap = d.trigger_snapshot?.profile_snapshot ?? {}
  const attrs = snap.attributes
  const raw = snap.name ?? (attrs && typeof attrs === 'object' ? (attrs as Record<string, unknown>).name : undefined)
  const name = typeof raw === 'string' && raw.trim() !== '' ? raw : null
  const masked = d.masked_paths.some((p) => p === 'trigger_snapshot.profile_snapshot.name' || p === 'trigger_snapshot.profile_snapshot.attributes.name')
  return { name, masked }
}

function messageRow(d: EnrollmentDetail, now: Date): SummaryRow {
  const q = '訊息有送出嗎？'
  const del = d.delivery_history
  const pending = del.filter((x) => x.status === 'PENDING')
  const sent = del.filter((x) => x.status === 'SENT')
  const failed = del.filter((x) => x.status === 'FAILED')
  const nameOf = (x: Delivery) => x.node_name ?? '發送訊息'
  if (pending.length > 0) {
    const waiting = d.status === 'WAITING' || d.status === 'ACTIVE' || d.status === 'PENDING'
    return {
      q,
      strong: waiting ? '還在排隊。' : '還沒有送達。',
      rest: `「${nameOf(pending[0])}」已排入發送、尚未送出。若超過 10 分鐘仍沒送出，請聯絡技術人員。`,
    }
  }
  if (sent.length > 0) {
    const extra = sent.length > 1 ? `共 ${sent.length} 則已送出。` : ''
    const after = d.status === 'ABORTED' ? '之後不會再有新的訊息。' : ''
    return { q, strong: '有。', rest: `「${nameOf(sent[0])}」已於 ${plainAt(sent[0].attempted_at, now)} 送出。${extra}${after}` }
  }
  if (failed.length > 0) return { q, strong: '沒有。', rest: '發送失敗，結果記在下方的訊息發送紀錄。' }
  if (del.length > 0) return { q, strong: '沒有。', rest: `有 ${del.length} 則訊息未發送，原因見下方的訊息發送紀錄。` }
  if (d.status === 'ABORTED') return { q, strong: '沒有，之後也不會送出。' }
  if (d.status === 'FAILED') return { q, strong: '沒有。', rest: '發生錯誤前沒有送出任何訊息。' }
  if (TERMINAL.has(d.status)) return { q, strong: '沒有。', rest: '這個旅程沒有發送過訊息。' }
  return { q, strong: '還沒有。', rest: '這位客戶還沒走到「發送訊息」的步驟，所以目前沒有收到任何訊息。' }
}

/** 摘要卡三問的答案（docs/ui/enrollment-trace.md「摘要組句」） */
export function buildSummary(d: EnrollmentDetail, def: JourneyVersion | null, defState: DefState, now: Date): SummaryVM {
  const q1 = '這位客戶現在在哪？'
  const q2 = '接下來會發生什麼？'
  const step = currentStepName(d, def)
  const pending = d.delivery_history.filter((x) => x.status === 'PENDING').length
  const msg = messageRow(d, now)
  const ended = d.terminated_at ? plainAt(d.terminated_at, now) : ''
  const next = upcomingSteps(d, def)[0]
  const noDef = defState !== 'ok' && defState !== 'loading' ? '暫時無法顯示後續步驟。' : undefined

  switch (d.status) {
    case 'WAITING': {
      const wc = d.wake_condition
      const open = [...d.trace].reverse().find((t) => t.exited_at === null)
      const waited = open ? `已等 ${formatDuration(now.getTime() - new Date(open.entered_at).getTime())}。` : undefined
      const nextText = next ? `，下一步是「${next.title}」` : ''
      let row2: SummaryRow
      if (wc?.wake_at) {
        const at = plainAt(wc.wake_at, now)
        row2 = defState === 'ok'
          ? { q: q2, strong: `${at} 之後`, rest: `自動繼續${nextText}。`, hint: '實際時間可能晚幾分鐘。' }
          : { q: q2, strong: `預計 ${at} 之後自動繼續。`, hint: noDef ?? '實際時間可能晚幾分鐘。' }
      } else if (wc && wc.type !== 'TIMER') {
        row2 = {
          q: q2,
          strong: '在等客戶的下一個動作。',
          rest: wc.timeout_at ? `${plainAt(wc.timeout_at, now)} 之後還沒等到，會改走「沒等到」的路線。` : undefined,
          hint: noDef,
        }
      } else {
        row2 = { q: q2, strong: '時間到了會自動繼續。', rest: next ? `下一步是「${next.title}」。` : undefined, hint: noDef }
      }
      return { tone: 'warning', rows: [{ q: q1, strong: `在「${step}」這一步，等待中。`, rest: waited }, row2, msg], diagnostic: false }
    }
    case 'ACTIVE':
    case 'PENDING':
      return {
        tone: 'info',
        rows: [{ q: q1, strong: `處理中，目前在「${step}」這一步。` }, { q: q2, strong: '通常幾秒內會到下一步。' }, msg],
        diagnostic: false,
      }
    case 'FAILED':
      return {
        tone: 'danger',
        rows: [
          { q: q1, strong: `停在「${step}」，因為發生錯誤。`, rest: ended ? `${ended} 中斷。` : undefined },
          { q: q2, strong: '不會自動繼續。', rest: '需要技術人員處理，請不要重複操作。' },
          msg,
        ],
        diagnostic: true,
      }
    case 'ABORTED':
      return {
        tone: 'neutral',
        rows: [
          { q: q1, strong: `進度已中止，停在「${step}」。` },
          { q: q2, strong: '不會再有任何步驟。', rest: `原因：這個旅程已被停止${ended ? `（${ended}）` : ''}。` },
          msg,
        ],
        diagnostic: false,
      }
    case 'SUSPENDED':
      return {
        tone: 'neutral',
        rows: [{ q: q1, strong: `暫停在「${step}」。` }, { q: q2, strong: '恢復前不會繼續。' }, msg],
        diagnostic: false,
      }
    default: {
      const first =
        d.status === 'GOAL_MET'
          ? { strong: '這位客戶已達成目標，旅程結束。' }
          : d.status === 'EXITED'
            ? { strong: '這位客戶已提前離開旅程。' }
            : { strong: '旅程已走完。' }
      const row2: SummaryRow =
        pending > 0
          ? { q: q2, strong: '不會再有新的步驟。', rest: `還有 ${pending} 則訊息在排隊，送出後這裡會更新。` }
          : { q: q2, strong: '沒有後續步驟。', rest: '旅程已經完整走完。' }
      return {
        tone: pending > 0 ? 'warning' : 'success',
        rows: [{ q: q1, ...first, rest: ended ? `${ended} 結束。` : undefined }, row2, msg],
        diagnostic: false,
      }
    }
  }
}

/** 管道名白話 */
export const channelLabel = (type: string) => channelText(type)

/** 「複製診斷資訊」：只含進度編號與時間，不含個資（enrollment-trace.md 狀態與互動） */
export function diagnosticText(d: EnrollmentDetail, now: Date): string {
  return [`進度編號：${d.enrollment_id}`, `資料時間（UTC）：${d.as_of}`, `複製時間（UTC）：${now.toISOString()}`].join('\n')
}
