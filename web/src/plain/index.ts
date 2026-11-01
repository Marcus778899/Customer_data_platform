import { formatLocal } from '../format'

/** 白話術語對照（D-079～D-081；plain.html#glossary）。未知值一律退回原值，不丟例外。 */
const lookup = (map: Record<string, string>) => (key: string) => map[key] ?? key

export const enrollmentStatusText = lookup({
  PENDING: '準備中',
  ACTIVE: '進行中',
  WAITING: '等待中',
  SUSPENDED: '已暫停',
  COMPLETED: '已走完',
  GOAL_MET: '已達成目標',
  EXITED: '已提前離開',
  FAILED: '發生錯誤',
  ABORTED: '已中止',
})

export const deliveryStatusText = lookup({
  PENDING: '等待發送',
  SENT: '已送出',
  FAILED: '發送失敗',
  CANCELLED: '已取消',
  SKIPPED: '未發送',
})

export const skipReasonText = lookup({
  CONSENT: '客戶未同意接收',
  FREQUENCY_CAP: '超過發送次數上限',
  QUIET_HOURS: '在免打擾時段',
  PRIORITY_LOST: '被更優先的訊息取代',
  BUDGET_EXCEEDED: '超出預算',
  KILL_SWITCH: '發送功能已被暫停',
})

export const channelText = lookup({ MOCK: '測試管道（不會真的送出）' })

export const outPortText = lookup({
  next: '往下一步',
  sent: '已送出後往下一步',
  yes: '符合',
  no: '不符合',
})

export const nodeTypeText = lookup({
  EventSourceNode: '進入旅程',
  WaitDelayNode: '等待',
  SendMessageNode: '發送訊息',
  EndNode: '旅程結束',
  ConditionNode: '條件判斷',
  SplitTestNode: 'A/B 分組',
  WaitForEventNode: '等待客戶行為',
  MergeNode: '分支合流',
  GoalNode: '達成目標',
  ExitNode: '提前結束',
})

/** ISO-8601 期間轉中文；無法解析原樣回傳 */
export function isoDurationText(iso: string): string {
  const m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(iso)
  if (!m || iso === 'P' || iso.endsWith('T')) return iso
  const units = ['天', '小時', '分鐘', '秒']
  const parts = units.flatMap((u, i) => (m[i + 1] ? [`${Number(m[i + 1])} ${u}`] : []))
  return parts.length ? parts.join(' ') : iso
}

/** 步驟標題；StartNode 不顯示（回 null） */
export function stepTitle(nodeType: string, nodeName: string | null, delay?: string | null): string | null {
  if (nodeType === 'StartNode') return null
  const base = nodeTypeText(nodeType) || '步驟'
  switch (nodeType) {
    case 'EndNode':
      return base
    case 'EventSourceNode':
      return nodeName ? `${nodeName}，${base}` : base
    case 'WaitDelayNode':
      return delay ? `等 ${isoDurationText(delay)}` : base
    default:
      return nodeName ? `${base}：${nodeName}` : base
  }
}

const ISO_UTC = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?Z/g

/** 文字內的 ISO UTC 時間換成本地時間 */
export const localizeIsoInText = (text: string) => text.replace(ISO_UTC, (s) => formatLocal(s))
