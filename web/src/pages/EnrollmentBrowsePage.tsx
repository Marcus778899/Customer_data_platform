import { useCallback, useState, type FormEvent } from 'react'
import { listEnrollments, listJourneys } from '../api/client'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox, MoreControls, Skeleton, Time } from '../components/ui'
import { ENROLLMENT_STATUS } from '../format'
import { usePagedList } from '../hooks'
import { navigate, type Loc } from '../router'
import { useFetch } from '../useFetch'

const STATUSES = Object.keys(ENROLLMENT_STATUS) as (keyof typeof ENROLLMENT_STATUS)[]

/** 最近 Enrollment 瀏覽（D-030）：不收 q，可依狀態、旅程篩選；精確查詢走 `/` */
export function EnrollmentBrowsePage({ loc }: { loc: Loc }) {
  const params = new URLSearchParams(loc.search)
  const status = params.get('status') ?? ''
  const journeyId = params.get('journey_id') ?? ''

  const load = useCallback(
    (cursor?: string) => listEnrollments({ status, journey_id: journeyId, cursor }),
    [status, journeyId],
  )
  const { items, next, error, loading, reload, loadMore, moreBusy, moreError, data } = usePagedList(load)

  const loadJourneys = useCallback(() => listJourneys({ limit: 100 }), [])
  const journeys = useFetch(loadJourneys)

  function submit(nextStatus: string, nextJourney: string) {
    const sp = new URLSearchParams()
    if (nextStatus) sp.set('status', nextStatus)
    if (nextJourney) sp.set('journey_id', nextJourney)
    const search = sp.toString() ? `?${sp.toString()}` : ''
    if (search === loc.search) reload()
    else navigate(`/enrollments${search}`)
  }

  return (
    <>
      <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '最近的 Enrollment' }]} />
      <div className="pagehead">
        <div>
          <h1>最近的 Enrollment</h1>
          <p className="muted">依進入時間新到舊。要查特定客戶或 Enrollment，請用精確查詢。</p>
        </div>
        <AppLink to="/">依 ID 查詢</AppLink>
      </div>
      <FilterForm
        key={loc.search}
        status={status}
        journeyId={journeyId}
        journeys={journeys.data?.items.map((j) => [j.journey_id, j.name] as const) ?? []}
        busy={loading}
        onSubmit={submit}
      />
      {loading && <Skeleton lines={4} />}
      {error && <ErrorBox title="無法載入 Enrollment。" error={error} onRetry={reload} />}
      {data && items.length === 0 && (
        <div className="card msg" role="status">
          <h2 className="sec">找不到符合條件的 Enrollment</h2>
          <p className="muted">試著放寬狀態或旅程條件。</p>
        </div>
      )}
      {data && items.length > 0 && (
        <>
          <div className="card scroll">
            <table className="stack">
              <caption>已載入 {items.length} 筆，依進入時間新到舊</caption>
              <thead>
                <tr>
                  <th scope="col">旅程（版本）</th>
                  <th scope="col">狀態</th>
                  <th scope="col">目前節點</th>
                  <th scope="col">進入時間</th>
                  <th scope="col">最後更新</th>
                </tr>
              </thead>
              <tbody>
                {items.map((r) => {
                  const st = ENROLLMENT_STATUS[r.status]
                  return (
                    <tr key={r.enrollment_id}>
                      <td data-label="旅程">
                        <AppLink className="tap" to={`/enrollments/${encodeURIComponent(r.enrollment_id)}`}>
                          {r.journey_name}（v{r.journey_version}）
                        </AppLink>
                      </td>
                      <td data-label="狀態">
                        <Badge tone={st.tone}>{st.label}</Badge>
                      </td>
                      <td data-label="目前節點">{r.current_node_name ?? r.current_node_type}</td>
                      <td data-label="進入時間" className="nw">
                        <Time iso={r.enrolled_at} />
                      </td>
                      <td data-label="最後更新" className="nw">
                        <Time iso={r.updated_at} />
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

function FilterForm({
  status,
  journeyId,
  journeys,
  busy,
  onSubmit,
}: {
  status: string
  journeyId: string
  journeys: (readonly [string, string])[]
  busy: boolean
  onSubmit: (status: string, journeyId: string) => void
}) {
  const [s, setS] = useState(status)
  const [j, setJ] = useState(journeyId)
  function handle(e: FormEvent) {
    e.preventDefault()
    if (!busy) onSubmit(s, j)
  }
  // 網址指定了旅程但清單還沒載到（或不在清單內）時，仍保留該選項
  const options = journeys.some(([id]) => id === j) || !j ? journeys : [...journeys, [j, j] as const]
  return (
    <form className="search card" aria-label="篩選 Enrollment" onSubmit={handle}>
      <div className="field">
        <label htmlFor="bj">旅程</label>
        <select id="bj" value={j} onChange={(e) => setJ(e.target.value)}>
          <option value="">全部</option>
          {options.map(([id, name]) => (
            <option key={id} value={id}>
              {name}
            </option>
          ))}
        </select>
      </div>
      <div className="field field-status">
        <label htmlFor="bs">狀態</label>
        <select id="bs" value={s} onChange={(e) => setS(e.target.value)}>
          <option value="">全部</option>
          {STATUSES.map((v) => (
            <option key={v} value={v}>
              {ENROLLMENT_STATUS[v].label}（{v}）
            </option>
          ))}
        </select>
      </div>
      <button className="btn" type="submit" aria-disabled={busy}>
        {busy ? '查詢中…' : '查詢'}
      </button>
    </form>
  )
}
