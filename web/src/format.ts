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
