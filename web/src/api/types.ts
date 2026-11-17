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
  /** journey-engine.md §4.1；舊回應可能沒有 */
  profile_name?: string | null
  profile_identifier?: { type: string; value: string; masked: boolean } | null
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

// 型別對應 docs/contracts/journeys.md §1
export type JourneyStatus = 'DRAFT' | 'ACTIVE' | 'ABORTED'
export interface UserRef {
  user_id: string
  display_name: string
}
export interface JourneySummary {
  journey_id: string
  name: string
  status: JourneyStatus
  latest_version: number | null
  has_draft: boolean
  review_status: 'PENDING' | 'REJECTED' | null
  updated_at: string
  published_at: string | null
  /** review_status 不為 null 時帶出那一筆審核的摘要（契約 §1）；舊資料可能沒有此欄 */
  latest_review?: ReviewBrief | null
}
export interface JourneyDetail extends JourneySummary {
  description: string | null
  versions: { version: number; published_at: string }[]
  stopped_at: string | null
  stop_reason: string | null
  stopped_by: UserRef | null
}
export interface JourneyNode {
  id: string
  type: string
  name: string | null
  config: Record<string, unknown>
  outputs: Record<string, string>
}
export interface JourneyVersion {
  journey_id: string
  name: string
  version: number
  description: string | null
  settings: Record<string, unknown>
  entry_node: string
  nodes: JourneyNode[]
  layout: { nodes: Record<string, { x: number; y: number }> } | null
  published_at: string
}

// 型別對應 docs/contracts/profiles.md §1（IdentifierType 可能出現未知值，以 string 接）
export interface Identifier {
  type: string
  value: string
  masked: boolean
}
export interface ProfileSummary {
  profile_id: string
  matched_by: string
  identifiers: Identifier[]
  created_at: string
  updated_at: string
}
export interface ProfileDetail {
  profile_id: string
  identifiers: Identifier[]
  created_at: string
  updated_at: string
  attributes: Record<string, unknown>
  masked_paths: string[]
  as_of: string
}

// 型別對應 docs/contracts/journeys.md §3（草稿、驗證）、§4（field-keys）
export interface DraftLayout {
  nodes: Record<string, { x: number; y: number }>
}
export interface DraftDefinition {
  name: string
  description: string | null
  settings: Record<string, unknown>
  entry_node: string
  nodes: JourneyNode[]
}
export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
/** 列表用的摘要（契約 §1 ReviewBrief） */
export interface ReviewBrief {
  review_id: string
  status: ReviewStatus
  submitted_by: UserRef | null
  submitted_at: string
  decided_by: UserRef | null
  decided_at: string | null
  reason: string | null
}
/** 契約 journeys.md §1 Review */
export interface Review extends ReviewBrief {
  journey_id: string
  action: string
  revision: number | null
  base_version: number | null
  note: string | null
  version: number | null
}
export interface Draft {
  journey_id: string
  revision: number
  definition: DraftDefinition
  layout: DraftLayout | null
  updated_at: string
  updated_by: UserRef | null
  latest_review: Review | null
}
export interface Issue {
  field: string
  message: string
  rule: number | null
  node_id: string | null
}
export interface ValidationReport {
  valid: boolean
  errors: Issue[]
  warnings: Issue[]
}
export interface FieldKeys {
  profile_attributes: string[]
  event_properties: string[]
  as_of: string
}

// docs/contracts/journeys.md §5
export interface NodeStat {
  node_id: string
  entered: number
  visits: number
  current: number
  out_ports: Record<string, number>
}
export interface NodeStats {
  journey_id: string
  version: number
  items: NodeStat[]
  as_of: string
}
