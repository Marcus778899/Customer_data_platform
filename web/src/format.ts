import type { DeliveryStatus, EnrollmentStatus } from './api/types'

const pad = (n: number) => String(n).padStart(2, '0')

/** ISO-8601 UTC 轉瀏覽器本地時區 `YYYY-MM-DD HH:mm[:ss]`（D-016） */
export function formatLocal(iso: string, seconds = false): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}${seconds ? `:${pad(d.getSeconds())}` : ''}`
  return `${date} ${time}`
}

export function utcOffsetLabel(now = new Date()): string {
  const off = -now.getTimezoneOffset()
  const sign = off >= 0 ? '+' : '-'
  const abs = Math.abs(off)
  return `UTC${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
}

export type Tone = 'info' | 'success' | 'warning' | 'danger' | 'neutral'

export const ENROLLMENT_STATUS: Record<EnrollmentStatus, { label: string; tone: Tone }> = {
  PENDING: { label: '待處理', tone: 'neutral' },
  ACTIVE: { label: '進行中', tone: 'info' },
  WAITING: { label: '等待中', tone: 'warning' },
  SUSPENDED: { label: '已暫停', tone: 'neutral' },
  COMPLETED: { label: '已完成', tone: 'success' },
  GOAL_MET: { label: '已達成目標', tone: 'success' },
  EXITED: { label: '已退出', tone: 'neutral' },
  FAILED: { label: '失敗', tone: 'danger' },
  ABORTED: { label: '已中止', tone: 'neutral' },
}

export const DELIVERY_STATUS: Record<DeliveryStatus, { label: string; tone: Tone }> = {
  PENDING: { label: '待送', tone: 'info' },
  SENT: { label: '已送出', tone: 'success' },
  FAILED: { label: '發送失敗', tone: 'danger' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
  SKIPPED: { label: '已略過', tone: 'neutral' },
}

export const TERMINAL: ReadonlySet<EnrollmentStatus> = new Set(['COMPLETED', 'GOAL_MET', 'EXITED', 'FAILED', 'ABORTED'])

/** 路徑組法同契約 §4.4：物件鍵用 `.`，陣列用 `[i]` */
export const childPath = (base: string, key: string) => (base ? `${base}.${key}` : key)
export const indexPath = (base: string, i: number) => `${base}[${i}]`

export const isPlainObject = (v: unknown): v is Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v)

export const JOURNEY_STATUS: Record<string, { label: string; tone: Tone }> = {
  DRAFT: { label: '草稿', tone: 'neutral' },
  ACTIVE: { label: '進行中', tone: 'success' },
  ABORTED: { label: '已停止', tone: 'danger' },
}

const IDENTIFIER_LABEL: Record<string, string> = {
  EMAIL: 'email',
  PHONE: '手機',
  MEMBER_ID: '會員編號',
  LINE_USER_ID: 'LINE 使用者 ID',
  DEVICE_ID: '裝置 ID',
  PROFILE_ID: '客戶 ID',
}
/** 未知的識別碼類型顯示原值（DESIGN-04 §5） */
export const identifierLabel = (type: string) => IDENTIFIER_LABEL[type] ?? type

/** 長 ID 只顯示頭尾，完整值放 title 或客戶頁 */
export function shortId(id: string): string {
  return id.length > 14 ? `${id.slice(0, 4)}…${id.slice(-6)}` : id
}

const WEEKDAY = ['日', '一', '二', '三', '四', '五', '六']
const DAY_MS = 86_400_000

/** 時長轉中文：「1 小時」「2 天 3 小時」「不到 1 分鐘」 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 60_000) return '不到 1 分鐘'
  const totalMin = Math.floor(ms / 60_000)
  const d = Math.floor(totalMin / 1440)
  const h = Math.floor((totalMin % 1440) / 60)
  const m = totalMin % 60
  const parts: string[] = []
  if (d > 0) {
    parts.push(`${d} 天`)
    if (h > 0) parts.push(`${h} 小時`)
  } else {
    if (h > 0) parts.push(`${h} 小時`)
    if (m > 0) parts.push(`${m} 分鐘`)
  }
  return parts.join(' ')
}

const dayStart = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()

/** 白話時間（D-079／D-016）：今天／昨天／明天以本地日曆日判斷 */
export function formatPlainTime(iso: string, now: Date): string {
  const d = new Date(iso)
  if (!iso || Number.isNaN(d.getTime())) return iso
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const dayDiff = Math.round((dayStart(d) - dayStart(now)) / DAY_MS)
  const diff = d.getTime() - now.getTime()
  const past = diff <= 0
  const abs = Math.abs(diff)
  if (dayDiff === 0) {
    if (past) {
      if (abs < 60_000) return `今天 ${hm}（剛剛）`
      if (abs < 3_600_000) return `今天 ${hm}（${Math.floor(abs / 60_000)} 分鐘前）`
      return `今天 ${hm}（${Math.floor(abs / 3_600_000)} 小時前）`
    }
    if (abs < 3_600_000) return `今天 ${hm}（約 ${Math.max(1, Math.round(abs / 60_000))} 分鐘後）`
    return `今天 ${hm}（約 ${Math.round(abs / 3_600_000)} 小時後）`
  }
  if (dayDiff === -1) return `昨天 ${hm}`
  if (dayDiff === 1) {
    const rel = abs < DAY_MS ? `（約 ${Math.max(1, Math.round(abs / 3_600_000))} 小時後）` : ''
    return `明天 ${hm}${rel}`
  }
  const md = `${d.getMonth() + 1}/${d.getDate()}（${WEEKDAY[d.getDay()]}）${hm}`
  const date = d.getFullYear() === now.getFullYear() ? md : `${d.getFullYear()}/${md}`
  if (!past) return `${date}（約 ${Math.max(1, Math.round(abs / DAY_MS))} 天後）`
  return date
}
