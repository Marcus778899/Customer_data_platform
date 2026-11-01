import { useState } from 'react'
import { ApiError, listDeliveries } from '../../api/client'
import type { Delivery, EnrollmentDetail } from '../../api/types'
import { Badge, ErrorBox } from '../../components/ui'
import { DELIVERY_STATUS } from '../../format'
import { deliveryStatusText, skipReasonText, stepTitle } from '../../plain'
import { channelLabel, plainAt } from './model'

export function Deliveries({ d, now }: { d: EnrollmentDetail; now: Date }) {
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
      <h2 className="sec">訊息發送紀錄</h2>
      {rows.length === 0 ? (
        <div className="card msg muted">
          <p>
            <strong>還沒有發出任何訊息。</strong>
          </p>
          <p>客戶走到「發送訊息」步驟時，這裡會出現每一則訊息和結果。</p>
        </div>
      ) : (
        <div className="card scroll">
          <table className="stack">
            <caption className="sr">這位客戶的訊息發送紀錄（新到舊，已載入 {rows.length} 筆）</caption>
            <thead>
              <tr>
                <th scope="col">時間</th>
                <th scope="col">哪一步</th>
                <th scope="col">管道</th>
                <th scope="col">結果</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((x) => (
                <tr key={x.idempotency_key}>
                  <td data-label="時間">{plainAt(x.attempted_at, now)}</td>
                  <td data-label="哪一步">{stepTitle('SendMessageNode', x.node_name)}</td>
                  <td data-label="管道">{channelLabel(x.channel_type)}</td>
                  <td data-label="結果">
                    <Badge tone={DELIVERY_STATUS[x.status].tone}>{deliveryStatusText(x.status)}</Badge>
                    {x.skip_reason && <span className="muted"> {skipReasonText(x.skip_reason)}</span>}
                  </td>
                </tr>
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
