// 審核紀錄（rv-history）：每筆 Review 一列、新到舊；已停止的旅程併入一列「停止旅程」
import { useCallback } from 'react'
import { getJourney, listReviews } from '../../api/client'
import { AppLink } from '../../components/AppLink'
import { Crumbs } from '../../components/Shell'
import { Badge, ErrorBox, Time } from '../../components/ui'
import { usePagedList } from '../../hooks'
import { useFetch } from '../../useFetch'
import { reviewRow, stopRow } from './model'

export function HistoryPage({ id }: { id: string }) {
  const loadDetail = useCallback(() => getJourney(id), [id])
  const detail = useFetch(loadDetail)
  const loadReviews = useCallback((cursor?: string) => listReviews(id, cursor), [id])
  const reviews = usePagedList(loadReviews)

  const dj = encodeURIComponent(id)
  const name = detail.data?.name
  const crumbs = (last: string) => (
    <Crumbs
      items={[
        { label: '旅程', to: '/journeys' },
        ...(name ? [{ label: name, to: `/journeys/${dj}` }] : []),
        { label: last },
      ]}
    />
  )

  const notFound = detail.error?.status === 404 || reviews.error?.status === 404
  if (notFound) {
    return (
      <>
        {crumbs('找不到')}
        <div className="card msg">
          <h1 className="h1-card">找不到這個旅程。</h1>
          <p>可能已被刪除，或網址有誤。</p>
          <p>
            <AppLink className="btn secondary" to="/journeys">
              回旅程列表
            </AppLink>
          </p>
        </div>
      </>
    )
  }

  const body = () => {
    if (reviews.error) return <ErrorBox title="暫時無法載入審核紀錄。" error={reviews.error} onRetry={reviews.reload} />
    if (detail.error) return <ErrorBox title="暫時無法載入審核紀錄。" error={detail.error} onRetry={detail.reload} />
    if (reviews.loading || !detail.data) {
      return (
        <div className="card" role="status" aria-label="載入審核紀錄中" aria-busy="true">
          <span className="skel" style={{ width: '40%' }} />
          <span className="skel" />
          <span className="skel" style={{ width: '70%' }} />
        </div>
      )
    }
    const stop = stopRow(detail.data)
    const rows = [...(stop ? [stop] : []), ...reviews.items.map(reviewRow)]
    if (rows.length === 0) {
      return (
        <div className="card msg">
          <p>還沒有審核紀錄。送審、核准、退回或停止後，會依時間出現在這裡。</p>
        </div>
      )
    }
    return (
      <>
        <ol className="timeline">
          {rows.map((r) => (
            <li key={r.key}>
              <p className="tl-head">
                <strong>{r.who}</strong> {r.action}・<Time iso={r.at} />
              </p>
              {r.detail && <p className="tl-detail">{r.detail}</p>}
            </li>
          ))}
        </ol>
        {reviews.moreError && (
          <div className="msg error" role="alert">
            <p>
              <strong>更多紀錄載入失敗</strong>
            </p>
            <p>已顯示的紀錄不受影響。</p>
            <button type="button" className="btn secondary" onClick={() => void reviews.loadMore()}>
              重試
            </button>
          </div>
        )}
        {!reviews.moreError && reviews.next && (
          <p>
            <button type="button" className="btn secondary" aria-disabled={reviews.moreBusy} onClick={() => !reviews.moreBusy && void reviews.loadMore()}>
              {reviews.moreBusy ? '載入中…' : '載入更多'}
            </button>
          </p>
        )}
      </>
    )
  }

  return (
    <>
      {crumbs('審核紀錄')}
      <div className="pagehead">
        <div>
          <h1>
            審核紀錄{' '}
            {detail.data?.status === 'ABORTED' && <Badge tone="danger">已停止</Badge>}
          </h1>
          <p className="muted">新到舊・紀錄不能修改或刪除</p>
        </div>
      </div>
      {body()}
    </>
  )
}
