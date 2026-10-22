import { useCallback, useEffect, useState } from 'react'
import { listJourneys } from '../api/client'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox, MoreControls, Skeleton, Time } from '../components/ui'
import { JOURNEY_STATUS } from '../format'
import { usePagedList } from '../hooks'
import { navigate, type Loc } from '../router'

const STATUS_CHIPS: [string, string][] = [
  ['', '全部'],
  ['DRAFT', '草稿'],
  ['ACTIVE', '進行中'],
  ['ABORTED', '已停止'],
]

/** 旅程列表（唯讀）：欄位依 JourneySummary；不顯示人數（D-050） */
export function JourneyListPage({ loc }: { loc: Loc }) {
  const params = new URLSearchParams(loc.search)
  const q = (params.get('q') ?? '').trim()
  const status = params.get('status') ?? ''
  const [qInput, setQInput] = useState(q)

  function go(nextQ: string, nextStatus: string) {
    const sp = new URLSearchParams()
    if (nextQ.trim()) sp.set('q', nextQ.trim())
    if (nextStatus) sp.set('status', nextStatus)
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

  const load = useCallback((cursor?: string) => listJourneys({ q, status, cursor }), [q, status])
  const { items, next, error, loading, reload, loadMore, moreBusy, moreError, data } = usePagedList(load)
  const filtered = q !== '' || status !== ''

  function clear() {
    setQInput('')
    navigate('/journeys', { replace: true })
  }

  return (
    <>
      <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '旅程清單' }]} />
      <div className="pagehead">
        <div>
          <h1>旅程</h1>
        </div>
        <AppLink to="/enrollments">最近的 Enrollment</AppLink>
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
      {data && items.length === 0 && !filtered && (
        <div className="card msg">
          <h2 className="sec">還沒有旅程</h2>
          <p>旅程由團隊協助建立並發佈後會出現在這裡。</p>
          <p className="muted">在網頁上建立與編輯旅程預計 v0.3.0 提供。</p>
        </div>
      )}
      {data && items.length === 0 && filtered && (
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
              <caption>已顯示 {items.length} 筆，依最後更新新到舊</caption>
              <thead>
                <tr>
                  <th scope="col">名稱</th>
                  <th scope="col">狀態</th>
                  <th scope="col">版本</th>
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
