// 型別對應 docs/contracts/journey-engine.md §2、§4.1（欄位名稱即契約欄位）
export type EnrollmentStatus =
  | 'PENDING'
  | 'ACTIVE'
  | 'WAITING'
  | 'SUSPENDED'
  | 'COMPLETED'
  | 'GOAL_MET'
  | 'EXITED'
  | 'FAILED'
  | 'ABORTED'
export type DeliveryStatus = 'PENDING' | 'SENT' | 'SKIPPED' | 'FAILED' | 'CANCELLED'

export interface Page<T> {
  items: T[]
  next_cursor: string | null
}

export interface EnrollmentSummary {
  enrollment_id: string
  journey_id: string
  profile_id: string
  current_node_id: string
  current_node_type: string
  journey_version: number
  journey_name: string
  current_node_name: string | null
  status: EnrollmentStatus
  enrolled_at: string
  updated_at: string
  terminated_at: string | null
}

export interface WakeCondition {
  type: 'TIMER' | 'EVENT' | 'SIGNAL'
  wake_at: string | null
  event_name: string | null
  signal_name: string | null
  timeout_at: string | null
}

export interface TraceEntry {
  seq: number
  node_id: string
  node_type: string
  node_name: string | null
  entered_at: string
  exited_at: string | null
  out_port: string | null
  decision_reason: string
  evaluated: Record<string, unknown>
}

export interface Delivery {
  idempotency_key: string
  node_id: string
  trace_seq: number
  node_name: string | null
  template_id: string
  channel_id: string
  channel_type: string
  message_type: 'TRANSACTIONAL' | 'MARKETING' | null
  status: DeliveryStatus
  skip_reason: string | null
  attempted_at: string
  delivery_id: string | null
}

export interface TriggerSnapshot {
  event_id: string
  event_name: string
  source_type: string
  occurred_at: string
  properties: Record<string, unknown>
  profile_snapshot: Record<string, unknown>
}

export interface EnrollmentDetail extends EnrollmentSummary {
  termination_reason: string | null
  wake_condition: WakeCondition | null
  variables: Record<string, unknown>
  trigger_snapshot: TriggerSnapshot
  trace: TraceEntry[]
  delivery_history: Delivery[]
  delivery_history_next_cursor: string | null
  as_of: string
  masked_paths: string[]
}

// 型別對應 docs/contracts/auth.md §3
export type Role = 'VIEWER' | 'EDITOR' | 'ADMIN'
export interface CurrentUser {
  user_id: string
  email: string
  display_name: string
  role: Role
}
