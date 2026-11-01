import { useCallback, useLayoutEffect, useMemo, useRef, type RefObject } from 'react'
import { ApiError, getEnrollment, getJourneyVersion } from '../api/client'
import type { EnrollmentDetail, JourneyVersion } from '../api/types'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox, RequestId, Skeleton } from '../components/ui'
import { ENROLLMENT_STATUS } from '../format'
import { GraphSection } from '../graph/GraphSection'
import { deriveOverlay } from '../graph/model'
import { enrollmentStatusText } from '../plain'
import { useFetch } from '../useFetch'
import { Advanced } from './trace/Advanced'
import { Deliveries } from './trace/Deliveries'
import { Icon } from './trace/Icons'
import { buildSteps, buildSummary, customerName, plainAt, type DefState } from './trace/model'
import { SummaryCard } from './trace/Summary'
import { Timeline } from './trace/Timeline'

/** 同一筆進度的請求還在途中時共用同一個 Promise（React StrictMode 開發時 effect 會跑兩次，避免重複打 API） */
const inflight = new Map<string, Promise<EnrollmentDetail>>()
function fetchEnrollment(id: string): Promise<EnrollmentDetail> {
  const existing = inflight.get(id)
  if (existing) return existing
  const p = getEnrollment(id).finally(() => inflight.delete(id))
  inflight.set(id, p)
  return p
}

/** 旅程進度（軌跡）頁：三問摘要＋白話步驟時間軸＋訊息紀錄＋進階資訊（D-081 方案 A） */
export function TracePage({ id }: { id: string }) {
  const load = useCallback(() => fetchEnrollment(id), [id])
  const { data, error, loading, last, reload } = useFetch(load)
  const shown = data ?? (last && last.enrollment_id === id ? last : undefined)

  // 以進度綁定的版本取定義（不是最新版）；進度失敗時不取
  const journeyId = shown?.journey_id
  const journeyVersion = shown?.journey_version
  const loadDef = useMemo(
    () =>
      journeyId !== undefined && journeyVersion !== undefined ? () => getJourneyVersion(journeyId, journeyVersion) : null,
    [journeyId, journeyVersion],
  )
  const def = useFetch(loadDef)
  // 首次載入時等旅程定義一起回來再顯示，避免「接下來」先空後補造成畫面跳動；重新整理時沿用舊畫面
  const waitingDef = shown !== undefined && def.loading && def.last === undefined

  const h1Ref = useRef<HTMLHeadingElement>(null)
  const focusedFor = useRef<string | null>(null)
  // layout effect：h1 掛上 DOM 的同一次 commit 就取得焦點，不被 scheduler 延後到使用者操作之後
  useLayoutEffect(() => {
    if (shown && !waitingDef && focusedFor.current !== shown.enrollment_id) {
      focusedFor.current = shown.enrollment_id
      h1Ref.current?.focus()
    }
  }, [shown, waitingDef])

  return (
    <>
      {(waitingDef || (!shown && !error)) && (
        <>
          <Crumbs items={[{ label: '客戶', to: '/customers' }, { label: '載入中…' }]} />
          <h1>載入中…</h1>
          {waitingDef ? (
            <div className="card" aria-busy="true">
              <span className="sr">載入中</span>
              <span className="skel" />
              <span className="skel" />
              <span className="skel" />
            </div>
          ) : (
            <Skeleton lines={4} />
          )}
        </>
      )}
      {error && <DetailError error={error} onRetry={reload} />}
      {shown && !waitingDef && (
        <DetailBody
          d={shown}
          h1Ref={h1Ref}
          refreshing={loading}
          onRefresh={reload}
          def={def.data}
          defError={def.error}
          defLoading={def.loading}
          onRetryDef={def.reload}
        />
      )}
    </>
  )
}

function DetailError({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  if (error.status === 404) {
    return (
      <>
        <Crumbs items={[{ label: '客戶', to: '/customers' }, { label: '找不到' }]} />
        <div className="msg card" role="alert">
          <h1 className="h1-card">找不到這筆進度</h1>
          <p className="muted">網址可能有誤，或這筆資料不存在。請從客戶頁重新進入。</p>
          {error.requestId && (
            <p className="muted">
              錯誤代碼 <span className="mono">{error.requestId}</span>
            </p>
          )}
          <AppLink className="btn secondary" to="/customers">
            回到客戶搜尋
          </AppLink>
        </div>
      </>
    )
  }
  return <ErrorBox title="無法載入旅程進度" error={error} onRetry={onRetry} />
}

function DetailBody({
  d,
  h1Ref,
  refreshing,
  onRefresh,
  def,
  defError,
  defLoading,
  onRetryDef,
}: {
  d: EnrollmentDetail
  h1Ref: RefObject<HTMLHeadingElement | null>
  refreshing: boolean
  onRefresh: () => void
  def: JourneyVersion | undefined
  defError: ApiError | undefined
  defLoading: boolean
  onRetryDef: () => void
}) {
  const now = new Date()
  const st = ENROLLMENT_STATUS[d.status]
  const usableDef = def !== undefined && Array.isArray(def.nodes) && def.nodes.length > 0 ? def : null
  const overlay = useMemo(
    () => (usableDef ? deriveOverlay(usableDef, d.trace, d.status) : null),
    [usableDef, d.trace, d.status],
  )
  const okDef = usableDef && overlay ? usableDef : null
  const defState: DefState = okDef ? 'ok' : defLoading ? 'loading' : defError ? 'failed' : def !== undefined ? 'mismatch' : 'loading'

  const summary = buildSummary(d, okDef, defState, now)
  const steps = buildSteps(d, okDef, now)
  const customer = customerName(d)
  const customerTo = `/customers/${encodeURIComponent(d.profile_id)}`
  const crumbName = customer.name ?? '這位客戶'
  const pendingMsgs = d.status === 'COMPLETED' && d.delivery_history.some((x) => x.status === 'PENDING')

  return (
    <div className="trace-page">
      <Crumbs items={[{ label: '客戶', to: '/customers' }, { label: crumbName, to: customerTo }, { label: '旅程進度' }]} />
      <header className="pagehead">
        <div>
          <h1 tabIndex={-1} ref={h1Ref}>
            「{d.journey_name}」旅程的進度
          </h1>
          <p className="muted">
            客戶：{customer.name ?? '未提供姓名'}
            {customer.masked && '（部分資料已遮罩）'} · {plainAt(d.enrolled_at, now)} 加入
          </p>
        </div>
        <div className="head-act">
          <Badge tone={st.tone}>{enrollmentStatusText(d.status)}</Badge>
          {pendingMsgs && <Badge tone="warning">有訊息待送</Badge>}
          <button className="btn secondary sm" type="button" onClick={onRefresh} aria-disabled={refreshing} aria-busy={refreshing}>
            <Icon name="refresh" size={16} />
            重新整理
          </button>
          <span className="muted">{plainAt(d.as_of, now)} 更新</span>
        </div>
      </header>

      <SummaryCard vm={summary} d={d} now={now} />

      {defState === 'failed' && (
        <div className="msg warn" role="alert">
          <p>
            <strong>旅程流程暫時載入失敗。</strong>
            目前只列出已走過的步驟，看不到接下來的步驟。 {defError && <RequestId error={defError} />}{' '}
            {defError && defError.status !== 404 && defError.status !== 403 && (
              <button className="btn secondary sm" type="button" onClick={onRetryDef}>
                重試
              </button>
            )}
          </p>
        </div>
      )}
      {defState === 'mismatch' && (
        <div className="msg warn" role="alert">
          <p>
            <strong>這筆進度和旅程流程對不上。</strong>
            所以只列出已走過的步驟。
          </p>
        </div>
      )}

      <Timeline steps={steps} />

      {okDef && overlay && (
        <details className="card flow-wrap">
          <summary>查看流程圖（圖形軌跡）</summary>
          <GraphSection
            def={okDef}
            overlay={overlay}
            label={`客戶的旅程軌跡圖（${enrollmentStatusText(d.status)}）（可橫向捲動；文字版見下方清單）`}
          />
        </details>
      )}

      <Deliveries d={d} now={now} />

      {d.masked_paths.length > 0 && (
        <p className="mask-note privacy" role="note">
          為了保護個資，姓名等欄位顯示為「已遮罩」，遮罩值無法解除，也不提供複製。
        </p>
      )}

      <Advanced d={d} />
    </div>
  )
}
