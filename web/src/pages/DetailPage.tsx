import { useCallback, useEffect, useRef, useState, type MouseEvent, type ReactNode, type RefObject } from 'react'
import { ApiError, getEnrollment, listDeliveries } from '../api/client'
import type { Delivery, EnrollmentDetail, TraceEntry } from '../api/types'
import { KeyValues } from '../components/KeyValues'
import { Badge, ErrorBox, Loading, RequestId, Time, Topbar, UtcContext } from '../components/ui'
import { DELIVERY_STATUS, ENROLLMENT_STATUS, TERMINAL, utcOffsetLabel } from '../format'
import { navigate } from '../router'
import { useFetch } from '../useFetch'

export function DetailPage({ id }: { id: string }) {
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

  function back(e: MouseEvent) {
    e.preventDefault()
    const state = window.history.state as { fromList?: boolean } | null
    if (state?.fromList) window.history.back()
    else navigate(shown ? `/?q=${encodeURIComponent(shown.profile_id)}` : '/')
  }

  return (
    <div className="app">
      <Topbar />
      <p>
        <a href="/" onClick={back}>
          &larr; 回到列表
        </a>
      </p>
      {!shown && loading && <Loading>載入軌跡中…</Loading>}
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
          />
        </UtcContext.Provider>
      )}
    </div>
  )
}

function DetailError({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  if (error.status === 404) {
    return (
      <div className="msg card" role="alert">
        <p>
          <strong>找不到這筆 Enrollment</strong>
        </p>
        <p className="muted">
          ID 可能有誤，或這筆紀錄不存在。 <RequestId error={error} />
        </p>
        <a
          href="/"
          onClick={(e) => {
            e.preventDefault()
            navigate('/')
          }}
        >
          回到查詢
        </a>
      </div>
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
}: {
  d: EnrollmentDetail
  h1Ref: RefObject<HTMLHeadingElement | null>
  refreshing: boolean
  onRefresh: () => void
  showUtc: boolean
  onToggleUtc: (v: boolean) => void
}) {
  const masked = new Set(d.masked_paths)
  const st = ENROLLMENT_STATUS[d.status]
  const terminal = TERMINAL.has(d.status)
  return (
    <>
      <header>
        <h1 tabIndex={-1} ref={h1Ref}>
          {d.journey_name}（v{d.journey_version}） <Badge tone={st.tone}>{st.label}</Badge>
        </h1>
        <p className="muted mono">
          {d.enrollment_id} 客戶 {d.profile_id}
        </p>
        {terminal && d.terminated_at && (
          <p className="muted">
            進入 <Time iso={d.enrolled_at} /> 結束 <Time iso={d.terminated_at} />
            {d.termination_reason && <span>（原因：{d.termination_reason}）</span>}
          </p>
        )}
        <p className="muted">
          <button className="btn secondary" type="button" onClick={onRefresh} aria-disabled={refreshing}>
            {refreshing ? '重新整理中…' : '重新整理'}
          </button>
           資料更新於 <Time iso={d.as_of} seconds />
        </p>
        <p className="muted tz-note">
          <label>
            <input type="checkbox" checked={showUtc} onChange={(e) => onToggleUtc(e.target.checked)} /> 同時顯示 UTC 原值
          </label>
          （時間以本機時區 {utcOffsetLabel()} 顯示）
        </p>
      </header>

      <Summary d={d} />

      {masked.size > 0 && (
        <p className="mask-note" role="note">
          標示「已遮罩」的值，系統基於個資保護不顯示完整內容，此頁無法解除。ID、時間與事件名稱不遮罩。
        </p>
      )}

      <h3>節點軌跡</h3>
      <ol className="trace" aria-label="節點軌跡，由舊到新">
        {d.trace.map((t, i) => (
          <TraceItem key={t.seq} t={t} index={i} current={!terminal && t.exited_at === null} masked={masked} />
        ))}
      </ol>

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
  current,
  masked,
}: {
  t: TraceEntry
  index: number
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
