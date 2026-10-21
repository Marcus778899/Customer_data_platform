import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { ApiError, getEnrollment, getJourneyVersion, listDeliveries } from '../api/client'
import type { Delivery, EnrollmentDetail, JourneyVersion, TraceEntry } from '../api/types'
import { AppLink } from '../components/AppLink'
import { KeyValues } from '../components/KeyValues'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox, RequestId, Skeleton, Time, UtcContext } from '../components/ui'
import { DELIVERY_STATUS, ENROLLMENT_STATUS, shortId, TERMINAL, utcOffsetLabel } from '../format'
import { GraphSection } from '../graph/GraphSection'
import { deriveOverlay } from '../graph/model'
import { useFetch } from '../useFetch'

/** 圖形軌跡（直線）：頁首＋目前狀況＋圖＋逐步紀錄＋發送歷史＋觸發快照；定義取不到時退回逐步紀錄 */
export function TracePage({ id }: { id: string }) {
  const load = useCallback(() => getEnrollment(id), [id])
  const { data, error, loading, last, reload } = useFetch(load)
  const shown = data ?? (last && last.enrollment_id === id ? last : undefined)
  const [showUtc, setShowUtc] = useState(false)

  const h1Ref = useRef<HTMLHeadingElement>(null)
  const focusedFor = useRef<string | null>(null)
  useEffect(() => {
    if (shown && focusedFor.current !== shown.enrollment_id) {
      focusedFor.current = shown.enrollment_id
      h1Ref.current?.focus()
    }
  }, [shown])

  // 以 Enrollment 綁定的版本取定義（不是最新版）；Enrollment 失敗時不取
  const journeyId = shown?.journey_id
  const journeyVersion = shown?.journey_version
  const loadDef = useMemo(
    () =>
      journeyId !== undefined && journeyVersion !== undefined ? () => getJourneyVersion(journeyId, journeyVersion) : null,
    [journeyId, journeyVersion],
  )
  const def = useFetch(loadDef)

  return (
    <>
      {!shown && !error && (
        <>
          <Crumbs items={[{ label: '客戶', to: '/customers' }, { label: '載入中…' }]} />
          <h1>載入中…</h1>
          <Skeleton lines={4} />
        </>
      )}
      {error && <DetailError error={error} onRetry={reload} />}
      {shown && (
        <UtcContext.Provider value={showUtc}>
          <DetailBody
            d={shown}
            h1Ref={h1Ref}
            refreshing={loading}
            onRefresh={reload}
            showUtc={showUtc}
            onToggleUtc={setShowUtc}
            def={def.data}
            defError={def.error}
            defLoading={def.loading}
            onRetryDef={def.reload}
          />
        </UtcContext.Provider>
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
          <h1 className="h1-card">找不到這筆 Enrollment</h1>
          <p className="muted">
            ID 可能有誤，或這筆紀錄不存在。 <RequestId error={error} />
          </p>
          <AppLink className="btn secondary" to="/">
            回到查詢
          </AppLink>
        </div>
      </>
    )
  }
  return <ErrorBox title="無法載入軌跡" error={error} onRetry={onRetry} />
}

function DetailBody({
  d,
  h1Ref,
  refreshing,
  onRefresh,
  showUtc,
  onToggleUtc,
  def,
  defError,
  defLoading,
  onRetryDef,
}: {
  d: EnrollmentDetail
  h1Ref: RefObject<HTMLHeadingElement | null>
  refreshing: boolean
  onRefresh: () => void
  showUtc: boolean
  onToggleUtc: (v: boolean) => void
  def: JourneyVersion | undefined
  defError: ApiError | undefined
  defLoading: boolean
  onRetryDef: () => void
}) {
  const masked = new Set(d.masked_paths)
  const st = ENROLLMENT_STATUS[d.status]
  const terminal = TERMINAL.has(d.status)
  const usableDef = def !== undefined && Array.isArray(def.nodes) && def.nodes.length > 0 ? def : null
  const overlay = useMemo(
    () => (usableDef ? deriveOverlay(usableDef, d.trace, d.status) : null),
    [usableDef, d.trace, d.status],
  )
  const customerTo = `/customers/${encodeURIComponent(d.profile_id)}`

  // 第 N 次經過：前端依 seq 計算（journey-engine §4.5）；只有同一節點出現多次才標示
  const visitOf = useMemo(() => {
    const total = new Map<string, number>()
    for (const t of d.trace) total.set(t.node_id, (total.get(t.node_id) ?? 0) + 1)
    const seen = new Map<string, number>()
    return d.trace.map((t) => {
      const n = (seen.get(t.node_id) ?? 0) + 1
      seen.set(t.node_id, n)
      return (total.get(t.node_id) ?? 0) > 1 ? n : 0
    })
  }, [d.trace])

  let graph: ReactNode = null
  if (usableDef && overlay) {
    graph = (
      <GraphSection
        def={usableDef}
        overlay={overlay}
        label={`客戶的旅程軌跡圖${terminal ? `（${st.label}）` : ''}（可橫向捲動；文字版見下方清單）`}
      />
    )
  } else if (defLoading) {
    graph = <p className="muted">載入流程圖中…</p>
  } else if (defError || def !== undefined) {
    const reason = defError
      ? `取得 v${d.journey_version} 流程定義失敗`
      : `軌跡與 v${d.journey_version} 流程定義對不上`
    graph = (
      <div className="msg warn" role="alert">
        <p>
          <strong>無法顯示旅程圖（{reason}）。</strong>
          下方改顯示逐步紀錄。 {defError && <RequestId error={defError} />}{' '}
          {defError && defError.status !== 404 && defError.status !== 403 && (
            <button className="btn secondary sm" type="button" onClick={onRetryDef}>
              重試
            </button>
          )}
        </p>
      </div>
    )
  }

  return (
    <>
      <Crumbs
        items={[
          { label: '客戶', to: '/customers' },
          { label: `客戶 ${shortId(d.profile_id)}`, to: customerTo },
          { label: d.journey_name },
        ]}
      />
      <div className="pagehead">
        <div>
          <h1 tabIndex={-1} ref={h1Ref}>
            {d.journey_name} <Badge tone={st.tone}>{st.label}</Badge>
          </h1>
          <p className="muted">
            客戶{' '}
            <AppLink className="mono" to={customerTo} title={d.profile_id}>
              {shortId(d.profile_id)}
            </AppLink>
            ・依 v{d.journey_version} 執行
          </p>
        </div>
        <div className="row">
          <label className="check">
            <input type="checkbox" checked={showUtc} onChange={(e) => onToggleUtc(e.target.checked)} />
            同時顯示 UTC 原值
          </label>
          <button className="btn secondary sm" type="button" onClick={onRefresh} aria-disabled={refreshing}>
            {refreshing ? '重新整理中…' : '重新整理'}
          </button>
        </div>
      </div>
      <dl className="kv" style={{ marginBottom: 'var(--space-4)' }}>
        <dt>Enrollment ID</dt>
        <dd className="mono">{d.enrollment_id}</dd>
        <dt>資料更新於</dt>
        <dd>
          <Time iso={d.as_of} seconds />
        </dd>
      </dl>
      <p className="muted tz-note">（時間以本機時區 {utcOffsetLabel()} 顯示）</p>

      <Summary d={d} />

      {graph}

      {masked.size > 0 && (
        <p className="mask-note" role="note">
          標示「已遮罩」的值，系統基於個資保護不顯示完整內容，遮罩值無法解除，也不提供複製。ID、時間與事件名稱不遮罩。
        </p>
      )}

      <div className="card" style={{ marginTop: 'var(--space-4)' }}>
        <h2 className="sec">逐步紀錄（舊到新）</h2>
        <ol className="trace" aria-label="節點軌跡，由舊到新">
          {d.trace.map((t, i) => (
            <TraceItem
              key={t.seq}
              t={t}
              index={i}
              visit={visitOf[i]}
              current={!terminal && t.exited_at === null}
              masked={masked}
            />
          ))}
        </ol>
      </div>

      {terminal && d.terminated_at && (
        <dl className="kv" style={{ marginTop: 'var(--space-4)' }}>
          <dt>結束時間</dt>
          <dd>
            <Time iso={d.terminated_at} seconds />
          </dd>
          {d.termination_reason && (
            <>
              <dt>結束原因</dt>
              <dd>{d.termination_reason}</dd>
            </>
          )}
        </dl>
      )}

      <Deliveries d={d} />
      <Trigger d={d} masked={masked} />
    </>
  )
}

function Summary({ d }: { d: EnrollmentDetail }) {
  const pending = d.delivery_history.filter((x) => x.status === 'PENDING').length
  const nodeName = d.current_node_name ?? d.current_node_type
  let tone = ''
  let body: ReactNode
  if (d.status === 'COMPLETED' && pending > 0) {
    body = `目前狀況：旅程已走完，但有 ${pending} 則訊息仍待送，尚未送出。`
  } else if (d.status === 'COMPLETED') {
    tone = 'ok'
    body = '目前狀況：旅程已走完，沒有待送的訊息。'
  } else if (d.status === 'WAITING' && d.wake_condition?.wake_at) {
    const at = d.wake_condition.wake_at
    body = (
      <>
        目前狀況：停在「{nodeName}」，預計 <Time iso={at} />（UTC {at}）繼續（實際喚醒最多晚一個輪詢間隔）。
      </>
    )
  } else if (d.status === 'FAILED') {
    body = `目前狀況：旅程執行失敗，停在「${nodeName}」。`
  } else {
    body = `目前狀況：${ENROLLMENT_STATUS[d.status].label}，目前在「${nodeName}」。`
  }
  return (
    <p className={`summary ${tone}`} role="status">
      {body}
    </p>
  )
}

function TraceItem({
  t,
  index,
  visit,
  current,
  masked,
}: {
  t: TraceEntry
  index: number
  /** 第幾次經過；0 = 只經過一次，不標示 */
  visit: number
  current: boolean
  masked: ReadonlySet<string>
}) {
  const count = Object.keys(t.evaluated).length
  return (
    <li className={current ? 'current' : undefined} aria-current={current ? 'step' : undefined}>
      <div className="head">
        <strong>
          {t.seq}. {t.node_name ?? t.node_type}
        </strong>
        {t.node_name === null && <span className="muted">（未命名）</span>}
        {visit > 0 && <Badge tone="info">第 {visit} 次經過</Badge>}
        {current && <Badge tone="warning">目前在此</Badge>}
        <span className="muted mono">
          {t.node_type} · {t.node_id}
        </span>
      </div>
      <div className="muted">
        <Time iso={t.entered_at} seconds /> → {t.exited_at ? <Time iso={t.exited_at} seconds /> : '尚未離開'} 出口：
        <span className="mono">{t.out_port ?? '—'}</span>
      </div>
      <p className="reason">原因：{t.decision_reason}</p>
      {count > 0 && (
        <details>
          <summary>判斷時取得的值（{count}）</summary>
          <KeyValues data={t.evaluated} basePath={`trace[${index}].evaluated`} masked={masked} />
        </details>
      )}
    </li>
  )
}

function Deliveries({ d }: { d: EnrollmentDetail }) {
  const [extra, setExtra] = useState<{ base: EnrollmentDetail; items: Delivery[]; next: string | null } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const mine = extra && extra.base === d ? extra : null
  const rows = mine ? [...d.delivery_history, ...mine.items] : d.delivery_history
  const next = mine ? mine.next : d.delivery_history_next_cursor

  async function loadEarlier() {
    if (!next) return
    setBusy(true)
    setError(null)
    try {
      const page = await listDeliveries(d.enrollment_id, next)
      setExtra({ base: d, items: [...(mine?.items ?? []), ...page.items], next: page.next_cursor })
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return
      setError(e instanceof ApiError ? e : new ApiError(0, null, null))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <h3>發送歷史</h3>
      {rows.length === 0 ? (
        <p className="msg muted card">尚無發送紀錄。</p>
      ) : (
        <div className="card scroll">
          <table className="stack">
            <caption className="muted">已載入 {rows.length} 筆，新到舊</caption>
            <thead>
              <tr>
                <th scope="col">時間</th>
                <th scope="col">節點</th>
                <th scope="col">模板 ID</th>
                <th scope="col">通道 ID（類型）</th>
                <th scope="col">訊息類型</th>
                <th scope="col">結果</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((x) => (
                <DeliveryRow key={x.idempotency_key} x={x} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      {error && <ErrorBox title="載入更早紀錄失敗" error={error} onRetry={loadEarlier} />}
      {next && !error && (
        <p>
          <button className="btn secondary" type="button" onClick={loadEarlier} aria-disabled={busy}>
            {busy ? '載入中…' : '載入更早紀錄'}
          </button>
        </p>
      )}
    </>
  )
}

function DeliveryRow({ x }: { x: Delivery }) {
  const st = DELIVERY_STATUS[x.status]
  return (
    <tr>
      <td data-label="時間">
        <Time iso={x.attempted_at} />
      </td>
      <td data-label="節點">
        {x.node_name ?? x.node_id}（第 {x.trace_seq} 步）
      </td>
      <td data-label="模板 ID" className="mono">
        {x.template_id}
      </td>
      <td data-label="通道" className="mono">
        {x.channel_id}（{x.channel_type}）
      </td>
      <td data-label="訊息類型">{x.message_type ?? '—'}</td>
      <td data-label="結果">
        <Badge tone={st.tone}>{st.label}</Badge>
        {x.skip_reason && (
          <>
            <br />
            <span className="mono muted">{x.skip_reason}</span>
          </>
        )}
        {x.delivery_id && (
          <>
            <br />
            <span className="mono muted">delivery_id: {x.delivery_id}</span>
          </>
        )}
      </td>
    </tr>
  )
}

function Trigger({ d, masked }: { d: EnrollmentDetail; masked: ReadonlySet<string> }) {
  const t = d.trigger_snapshot
  const hasProps = Object.keys(t.properties).length > 0
  const hasProfile = Object.keys(t.profile_snapshot).length > 0
  const hasVars = Object.keys(d.variables).length > 0
  return (
    <>
      <h3>觸發事件</h3>
      <details className="card">
        <summary>展開觸發當下的事件快照</summary>
        <dl className="kv">
          <dt>事件</dt>
          <dd>
            {t.event_name}（<Time iso={t.occurred_at} seconds />）
          </dd>
          <dt>事件 ID</dt>
          <dd className="mono">{t.event_id}</dd>
          <dt>來源類型</dt>
          <dd className="mono">{t.source_type}</dd>
        </dl>
        <h4>事件屬性</h4>
        {hasProps ? (
          <KeyValues data={t.properties} basePath="trigger_snapshot.properties" masked={masked} />
        ) : (
          <p className="muted">此事件沒有屬性。</p>
        )}
        <h4>客戶屬性快照</h4>
        {hasProfile ? (
          <KeyValues data={t.profile_snapshot} basePath="trigger_snapshot.profile_snapshot" masked={masked} />
        ) : (
          <p className="muted">此版本未記錄客戶屬性快照。</p>
        )}
        {hasVars && (
          <>
            <h4>旅程變數</h4>
            <KeyValues data={d.variables} basePath="variables" masked={masked} />
          </>
        )}
      </details>
    </>
  )
}
