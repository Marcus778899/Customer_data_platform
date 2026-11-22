import { useCallback, useEffect, useState } from 'react'
import { listJourneys } from '../api/client'
import { AppLink } from '../components/AppLink'
import { FlashBar } from '../components/Flash'
import { Crumbs } from '../components/Shell'
import { useAuth } from '../auth'
import { Badge, ErrorBox, MoreControls, Skeleton, Time } from '../components/ui'
import { JOURNEY_STATUS } from '../format'
import { usePagedList } from '../hooks'
import { navigate, type Loc } from '../router'
import { waitText } from './review/model'

/** 篩選鈕：value 是 `status=…` 或 `review_status=…`（空字串 = 全部） */
const STATUS_CHIPS: [string, string][] = [
  ['', '全部'],
  ['status=DRAFT', '草稿'],
  ['status=ACTIVE', '進行中'],
  ['review_status=PENDING', '待審核'],
  ['review_status=REJECTED', '已退回'],
  ['status=ABORTED', '已停止'],
]

/** 旅程列表（唯讀）：欄位依 JourneySummary；不顯示人數（D-050） */
export function JourneyListPage({ loc }: { loc: Loc }) {
  const params = new URLSearchParams(loc.search)
  const q = (params.get('q') ?? '').trim()
  const reviewStatus = params.get('review_status') === 'PENDING' || params.get('review_status') === 'REJECTED' ? params.get('review_status')! : ''
  const status = reviewStatus ? `review_status=${reviewStatus}` : params.get('status') ? `status=${params.get('status')}` : ''
  const { user } = useAuth()
  const [qInput, setQInput] = useState(q)

  function go(nextQ: string, nextStatus: string) {
    const sp = new URLSearchParams()
    if (nextQ.trim()) sp.set('q', nextQ.trim())
    if (nextStatus) {
      const [k, v] = nextStatus.split('=')
      sp.set(k, v)
    }
    const search = sp.toString() ? `?${sp.toString()}` : ''
    if (search !== loc.search) navigate(`/journeys${search}`, { replace: true })
  }

  // 輸入停頓後才查詢，並同步網址
  useEffect(() => {
    if (qInput.trim() === q) return
    const t = window.setTimeout(() => go(qInput, status), 300)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qInput])

  const load = useCallback(
    (cursor?: string) =>
      listJourneys({
        q,
        status: status.startsWith('status=') ? status.slice(7) : undefined,
        review_status: reviewStatus || undefined,
        cursor,
      }),
    [q, status, reviewStatus],
  )
  const { items, next, error, loading, reload, loadMore, moreBusy, moreError, data } = usePagedList(load)
  const filtered = q !== '' || status !== ''

  function clear() {
    setQInput('')
    navigate('/journeys', { replace: true })
  }

  return (
    <>
      <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '旅程清單' }]} />
      <FlashBar />
      <div className="pagehead">
        <div>
          <h1>旅程</h1>
        </div>
        <div className="head-act">
          {user.role !== 'VIEWER' && (
            <AppLink className="btn" to="/journeys/new">
              新增旅程
            </AppLink>
          )}
          <AppLink to="/enrollments">最近的旅程進度</AppLink>
        </div>
      </div>
      <div className="toolbar">
        <div className="field" style={{ flex: '1 1 280px' }}>
          <label htmlFor="jq">搜尋旅程名稱</label>
          <input id="jq" type="search" value={qInput} placeholder="例：棄單" onChange={(e) => setQInput(e.target.value)} />
        </div>
        <fieldset className="chips">
          <legend>狀態</legend>
          {STATUS_CHIPS.map(([v, label]) => (
            <button
              key={v}
              className="chip"
              type="button"
              aria-pressed={status === v}
              onClick={() => go(qInput, v)}
            >
              {label}
            </button>
          ))}
        </fieldset>
      </div>

      {loading && <Skeleton lines={4} />}
      {error && <ErrorBox title="無法載入旅程列表。" error={error} onRetry={reload} />}
      {data && items.length === 0 && reviewStatus && q === '' && (
        <div className="card msg" role="status">
          <h2 className="sec">{reviewStatus === 'PENDING' ? '目前沒有待審核的旅程。' : '目前沒有已退回的旅程。'}</h2>
          <p>{reviewStatus === 'PENDING' ? '有人送審後會出現在這裡。' : '被退回的旅程會出現在這裡。'}</p>
          <p>
            <button className="btn secondary" type="button" onClick={clear}>
              看全部旅程
            </button>
          </p>
        </div>
      )}
      {data && items.length === 0 && !filtered && (
        <div className="card msg">
          <h2 className="sec">還沒有旅程</h2>
          <p>旅程由團隊協助建立並發佈後會出現在這裡。</p>
        </div>
      )}
      {data && items.length === 0 && filtered && !(reviewStatus && q === '') && (
        <div className="card msg" role="status">
          <h2 className="sec">沒有符合條件的旅程</h2>
          <p>
            狀態「{STATUS_CHIPS.find(([v]) => v === status)?.[1] ?? status}」
            {q && <>、名稱含「{q}」</>}。
          </p>
          <p>
            <button className="btn secondary" type="button" onClick={clear}>
              清除搜尋
            </button>
          </p>
        </div>
      )}
      {data && items.length > 0 && (
        <>
          <div className="card scroll">
            <table className="stack">
              <caption>
                {reviewStatus
                  ? `${reviewStatus === 'PENDING' ? '待審核' : '已退回'}的旅程，${next ? '已顯示' : '共'} ${items.length} 個（新到舊）`
                  : `已顯示 ${items.length} 筆，依最後更新新到舊`}
              </caption>
              <thead>
                <tr>
                  <th scope="col">名稱</th>
                  <th scope="col">狀態</th>
                  <th scope="col">版本</th>
                  {reviewStatus && <th scope="col">審核</th>}
                  {reviewStatus && <th scope="col">{reviewStatus === 'PENDING' ? '送審者與等待時間' : '退回者與時間'}</th>}
                  <th scope="col">最後更新</th>
                  <th scope="col">最近發佈</th>
                </tr>
              </thead>
              <tbody>
                {items.map((j) => {
                  const st = JOURNEY_STATUS[j.status] ?? { label: j.status, tone: 'neutral' as const }
                  return (
                    <tr key={j.journey_id}>
                      <td data-label="名稱">
                        <AppLink className="tap" to={`/journeys/${encodeURIComponent(j.journey_id)}`}>
                          {j.name}
                        </AppLink>
                      </td>
                      <td data-label="狀態">
                        <Badge tone={st.tone}>{st.label}</Badge>
                      </td>
                      <td data-label="版本" className="nw">
                        {j.latest_version === null ? '—' : `v${j.latest_version}`}
                      </td>
                      {reviewStatus && (
                        <td data-label="審核">
                          <Badge tone={reviewStatus === 'PENDING' ? 'info' : 'warning'}>
                            {reviewStatus === 'PENDING' ? '審核中' : '已退回'}
                          </Badge>{' '}
                          <span className="nw">草稿 v{(j.latest_version ?? 0) + 1}</span>
                        </td>
                      )}
                      {reviewStatus && (
                        <td data-label={reviewStatus === 'PENDING' ? '送審者與等待時間' : '退回者與時間'}>
                          {j.latest_review ? (
                            reviewStatus === 'PENDING' ? (
                              <>
                                {j.latest_review.submitted_by?.display_name ?? '管理金鑰'}・{waitText(j.latest_review.submitted_at)}
                              </>
                            ) : (
                              <>
                                {j.latest_review.decided_by?.display_name ?? '管理員'}{' '}
                                {j.latest_review.decided_at ? <Time iso={j.latest_review.decided_at} /> : null} 退回
                              </>
                            )
                          ) : (
                            '—'
                          )}
                        </td>
                      )}
                      <td data-label="最後更新" className="nw">
                        <Time iso={j.updated_at} />
                      </td>
                      <td data-label="最近發佈" className="nw">
                        {j.published_at ? <Time iso={j.published_at} /> : '—'}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <MoreControls next={next} busy={moreBusy} error={moreError} onMore={loadMore} />
        </>
      )}
    </>
  )
}
