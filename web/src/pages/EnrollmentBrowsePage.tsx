import { useCallback, useState, type FormEvent } from 'react'
import { listEnrollments, listJourneys } from '../api/client'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox, MoreControls, Skeleton } from '../components/ui'
import type { EnrollmentStatus, EnrollmentSummary } from '../api/types'
import { ENROLLMENT_STATUS } from '../format'
import { enrollmentStatusText, stepTitle } from '../plain'
import { usePagedList } from '../hooks'
import { navigate, type Loc } from '../router'
import { useFetch } from '../useFetch'

const STATUSES = Object.keys(ENROLLMENT_STATUS) as (keyof typeof ENROLLMENT_STATUS)[]

/** 旅程進度瀏覽（D-030）：不收 q，可依狀態、旅程篩選；精確查詢走 `/` */
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
      <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '旅程進度' }]} />
      <div className="pagehead">
        <div>
          <h1>旅程進度</h1>
          <p className="muted">最近加入旅程的客戶，新到舊。要查特定客戶，請用精確查詢。</p>
        </div>
        <AppLink to="/">用客戶 ID 查詢</AppLink>
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
      {error && <ErrorBox title="無法載入旅程進度。" error={error} onRetry={reload} />}
      {data && items.length === 0 && (
        <div className="card msg" role="status">
          <h2 className="sec">找不到符合條件的旅程進度</h2>
          <p className="muted">試著放寬狀態或旅程條件。</p>
        </div>
      )}
      {data && items.length > 0 && (
        <>
          <div className="card scroll">
            <table className="stack">
              <caption>已載入 {items.length} 筆，新到舊</caption>
              <thead>
                <tr>
                  <th scope="col">客戶</th>
                  <th scope="col">旅程</th>
                  <th scope="col">狀態</th>
                  <th scope="col">目前在哪一步</th>
                  <th scope="col">下一步</th>
                </tr>
              </thead>
              <tbody>
                {items.map((r) => (
                  <tr key={r.enrollment_id}>
                    <td data-label="客戶">
                      <CustomerLink profileId={r.profile_id} />
                    </td>
                    <td data-label="旅程">
                      <AppLink className="tap" to={`/enrollments/${encodeURIComponent(r.enrollment_id)}`}>
                        {r.journey_name}
                      </AppLink>
                    </td>
                    <td data-label="狀態">
                      <StatusBadge status={r.status} />
                    </td>
                    <td data-label="目前在哪一步">{stepText(r)}</td>
                    <td data-label="下一步">{nextText(r.status)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <MoreControls next={next} busy={moreBusy} error={moreError} onMore={loadMore} />
        </>
      )}
    </>
  )
}

/** 目前（或最後）在哪一步的白話文字；節點型別不直接顯示 */
export const stepText = (r: EnrollmentSummary) => stepTitle(r.current_node_type, r.current_node_name) ?? '旅程開始'

/** 列表「下一步」：摘要沒有 wake_at 與旅程定義，只依狀態給一句白話 */
export function nextText(status: EnrollmentStatus): string {
  switch (status) {
    case 'WAITING':
      return '時間到會自動繼續'
    case 'ACTIVE':
    case 'PENDING':
      return '處理中'
    case 'FAILED':
      return '需要技術人員處理'
    case 'SUSPENDED':
      return '已暫停'
    default:
      return '—'
  }
}

/** 狀態徽章：顏色沿用 ENROLLMENT_STATUS，文字用白話 */
export function StatusBadge({ status }: { status: EnrollmentStatus }) {
  return <Badge tone={ENROLLMENT_STATUS[status].tone}>{enrollmentStatusText(status)}</Badge>
}

/** 客戶欄：摘要只有 profile_id，不顯示 ID，改為連到客戶頁 */
export function CustomerLink({ profileId }: { profileId: string }) {
  return (
    <AppLink className="tap" to={`/customers/${encodeURIComponent(profileId)}`}>
      查看客戶
    </AppLink>
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
    <form className="search card" aria-label="篩選旅程進度" onSubmit={handle}>
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
              {enrollmentStatusText(v)}
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
