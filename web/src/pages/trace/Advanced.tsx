import type { KeyboardEvent, ReactNode } from 'react'
import type { EnrollmentDetail } from '../../api/types'
import { KeyValues } from '../../components/KeyValues'
import { CopyButton } from '../../components/ui'
import { formatLocal, utcOffsetLabel } from '../../format'
import { localizeIsoInText } from '../../plain'

const both = (iso: string) => `${formatLocal(iso, true)}（UTC ${iso}）`

/** 原生 summary 的 Enter 啟動在部分環境（含 jsdom）不會觸發；自行切換並阻止預設，避免瀏覽器重複切換 */
function toggleOnEnter(e: KeyboardEvent<HTMLElement>) {
  if (e.key !== 'Enter') return
  const box = e.currentTarget.parentElement
  if (box instanceof HTMLDetailsElement) {
    e.preventDefault()
    box.open = !box.open
  }
}

function Row({ k, children }: { k: string; children: ReactNode }) {
  return (
    <>
      <dt>{k}</dt>
      <dd>{children}</dd>
    </>
  )
}

/** 進階資訊：ID、技術名稱、UTC 原值、判斷值、系統原因文字、觸發事件（預設收合） */
export function Advanced({ d }: { d: EnrollmentDetail }) {
  const masked = new Set(d.masked_paths)
  const t = d.trigger_snapshot
  const wc = d.wake_condition
  return (
    <details className="card adv">
      <summary onKeyDown={toggleOnEnter}>進階資訊（給技術人員排查問題用）</summary>
      <p className="muted">一般使用不需要看這一區。時間以你的瀏覽器時區（{utcOffsetLabel()}）顯示，括號內為 UTC 原值。</p>
      <dl className="kv">
        <Row k="進度編號">
          <span className="mono">{d.enrollment_id}</span> <CopyButton text={d.enrollment_id} label="複製進度編號" />
        </Row>
        <Row k="客戶 ID">
          <span className="mono">{d.profile_id}</span>
        </Row>
        <Row k="旅程">
          {d.journey_name}（第 {d.journey_version} 版）<span className="mono"> · {d.journey_id}</span>
        </Row>
        <Row k="加入時間">{both(d.enrolled_at)}</Row>
        {wc?.wake_at && <Row k="預計繼續時間">{both(wc.wake_at)}</Row>}
        {wc?.timeout_at && <Row k="逾時時間">{both(wc.timeout_at)}</Row>}
        {d.terminated_at && <Row k="結束時間">{both(d.terminated_at)}</Row>}
        {d.termination_reason && <Row k="結束原因（系統文字）">{localizeIsoInText(d.termination_reason)}</Row>}
        <Row k="目前步驟的技術名稱">
          <span className="mono">
            {d.current_node_type} · {d.current_node_id}
          </span>
        </Row>
        <Row k="資料更新時間（as_of）">{both(d.as_of)}</Row>
      </dl>
      {masked.size > 0 && (
        <>
          <h4>已遮罩的欄位（遮罩值無法解除，也不提供複製）</h4>
          <ul className="mono">
            {[...masked].map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </>
      )}
      <h4>每一步的技術細節</h4>
      {d.trace.map((s, i) => (
        <div key={s.seq} className="tech-step">
          <p>
            <strong>
              {s.seq}.{' '}
              <span className="mono">
                {s.node_type} · {s.node_id}
              </span>
            </strong>
          </p>
          <dl className="kv">
            <Row k="進入時間">{both(s.entered_at)}</Row>
            <Row k="離開時間">{s.exited_at ? both(s.exited_at) : '尚未離開'}</Row>
            <Row k="出口">
              <span className="mono">{s.out_port ?? '—'}</span>
            </Row>
            <Row k="系統原因文字">{localizeIsoInText(s.decision_reason)}</Row>
          </dl>
          {Object.keys(s.evaluated).length > 0 && (
            <details>
              <summary>判斷時取得的值（{Object.keys(s.evaluated).length}）</summary>
              <KeyValues data={s.evaluated} basePath={`trace[${i}].evaluated`} masked={masked} />
            </details>
          )}
        </div>
      ))}
      <h4>觸發事件</h4>
      <dl className="kv">
        <Row k="事件">
          {t.event_name}（{both(t.occurred_at)}）
        </Row>
        <Row k="事件 ID">
          <span className="mono">{t.event_id}</span>
        </Row>
        <Row k="來源類型">
          <span className="mono">{t.source_type}</span>
        </Row>
      </dl>
      <h4>事件屬性</h4>
      {Object.keys(t.properties).length > 0 ? (
        <KeyValues data={t.properties} basePath="trigger_snapshot.properties" masked={masked} />
      ) : (
        <p className="muted">此事件沒有屬性。</p>
      )}
      <h4>客戶屬性快照</h4>
      {Object.keys(t.profile_snapshot).length > 0 ? (
        <KeyValues data={t.profile_snapshot} basePath="trigger_snapshot.profile_snapshot" masked={masked} />
      ) : (
        <p className="muted">此版本未記錄客戶屬性快照。</p>
      )}
      {Object.keys(d.variables).length > 0 && (
        <>
          <h4>旅程變數</h4>
          <KeyValues data={d.variables} basePath="variables" masked={masked} />
        </>
      )}
    </details>
  )
}
