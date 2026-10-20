import { useCallback, useEffect, useRef, useState, type FormEvent, type MouseEvent } from 'react'
import { ApiError, listEnrollments } from '../api/client'
import type { EnrollmentSummary, Page } from '../api/types'
import { Badge, ErrorBox, Loading, Time } from '../components/ui'
import { ENROLLMENT_STATUS } from '../format'
import { lastNavigationWasPop, navigate, type Loc } from '../router'
import { useFetch } from '../useFetch'

const STATUS_OPTIONS: [string, string][] = [
  ['', '全部'],
  ['ACTIVE', '進行中（ACTIVE）'],
  ['WAITING', '等待中（WAITING）'],
  ['COMPLETED', '已完成（COMPLETED）'],
  ['FAILED', '失敗（FAILED）'],
]

const LAST_ROW_KEY = 'cdp.lastRow'

export function SearchPage({ loc }: { loc: Loc }) {
  const params = new URLSearchParams(loc.search)
  const q = (params.get('q') ?? '').trim()
  const status = params.get('status') ?? ''

  const load = useCallback(() => listEnrollments({ q, status }), [q, status])
  const { data, error, loading, reload } = useFetch(q ? load : null)

  const [more, setMore] = useState<{ base: Page<EnrollmentSummary>; items: EnrollmentSummary[]; next: string | null } | null>(
    null,
  )
  const [moreLoading, setMoreLoading] = useState(false)
  const [moreError, setMoreError] = useState<ApiError | null>(null)

  const headingRef = useRef<HTMLHeadingElement>(null)
  const focusHeading = useRef(false)
  const restoreRow = useRef(lastNavigationWasPop())

  // 送出查詢後焦點移到結果標題；從詳情按上一頁回來時焦點回到剛點的那一列
  useEffect(() => {
    if (!data) return
    if (focusHeading.current) {
      focusHeading.current = false
      headingRef.current?.focus()
    } else if (restoreRow.current) {
      restoreRow.current = false
      const id = sessionStorage.getItem(LAST_ROW_KEY)
      if (id) [...document.querySelectorAll<HTMLElement>('a[data-eid]')].find((a) => a.dataset.eid === id)?.focus()
    }
  }, [data])

  function submit(nextQ: string, nextStatus: string) {
    const trimmed = nextQ.trim()
    focusHeading.current = true
    const sp = new URLSearchParams()
    if (trimmed) sp.set('q', trimmed)
    if (nextStatus) sp.set('status', nextStatus)
    const search = sp.toString() ? `?${sp.toString()}` : ''
    if (search === loc.search) reload()
    else navigate(`/${search}`)
  }

  const rows = data ? (more && more.base === data ? [...data.items, ...more.items] : data.items) : []
  const nextCursor = data ? (more && more.base === data ? more.next : data.next_cursor) : null

  async function loadMore() {
    if (!data || !nextCursor) return
    setMoreLoading(true)
    setMoreError(null)
    try {
      const page = await listEnrollments({ q, status, cursor: nextCursor })
      setMore({ base: data, items: [...(more && more.base === data ? more.items : []), ...page.items], next: page.next_cursor })
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return
      setMoreError(e instanceof ApiError ? e : new ApiError(0, null, null))
    } finally {
      setMoreLoading(false)
    }
  }

  return (
    <>
      <header>
        <h1>客戶旅程軌跡</h1>
        <p className="muted">唯讀。查某位客戶走到哪、為什麼沒收到訊息。</p>
      </header>
      <SearchForm key={loc.search} q={q} status={status} busy={loading} onSubmit={submit} />

      {!q && <p className="msg muted">輸入客戶 ID 或 Enrollment ID 開始查詢。</p>}
      {q && loading && <Loading>載入中…</Loading>}
      {q && error && (
        <div style={{ marginTop: 'var(--space-4)' }}>
          <ErrorBox title="查詢失敗，沒有取得資料" error={error} onRetry={reload} />
        </div>
      )}
      {data && rows.length === 0 && (
        <div className="msg card" style={{ marginTop: 'var(--space-4)' }} role="status">
          <p>
            <strong>找不到「{q}」的任何旅程紀錄</strong>
          </p>
          <p className="muted">
            可能原因：ID 輸入有誤；這位客戶尚未觸發任何旅程；或該事件發生已超過 24 小時，系統收下但不建立旅程紀錄。
          </p>
        </div>
      )}
      {data && rows.length > 0 && (
        <section style={{ marginTop: 'var(--space-4)' }}>
          <h2 tabIndex={-1} ref={headingRef} className="result-heading">
            查詢結果
          </h2>
          <div className="card scroll">
            <table className="stack">
              <caption>
                {nextCursor ? `已載入 ${rows.length} 筆` : `共 ${rows.length} 筆`}，依進入時間新到舊
              </caption>
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
                {rows.map((r) => (
                  <Row key={r.enrollment_id} r={r} />
                ))}
              </tbody>
            </table>
          </div>
          {moreError && (
            <div style={{ marginTop: 'var(--space-3)' }}>
              <ErrorBox title="載入更多失敗" error={moreError} onRetry={loadMore} />
            </div>
          )}
          {nextCursor && !moreError && (
            <p>
              <button className="btn secondary" type="button" onClick={loadMore} aria-disabled={moreLoading}>
                {moreLoading ? '載入中…' : '載入更多'}
              </button>
            </p>
          )}
        </section>
      )}
    </>
  )
}

function SearchForm({
  q,
  status,
  busy,
  onSubmit,
}: {
  q: string
  status: string
  busy: boolean
  onSubmit: (q: string, status: string) => void
}) {
  const [qInput, setQInput] = useState(q)
  const [statusInput, setStatusInput] = useState(status)
  function handle(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    onSubmit(qInput, statusInput)
  }
  return (
    <form className="search card" role="search" aria-label="查詢 Enrollment" onSubmit={handle}>
      <div className="field">
        <label htmlFor="q">客戶 ID 或 Enrollment ID</label>
        <input
          id="q"
          name="q"
          value={qInput}
          placeholder="例：01JCXYZPROFILE00000000001（精確比對）"
          onChange={(e) => setQInput(e.target.value)}
        />
      </div>
      {/* 狀態篩選永遠顯示（D-015） */}
      <div className="field field-status">
        <label htmlFor="status">狀態</label>
        <select id="status" value={statusInput} onChange={(e) => setStatusInput(e.target.value)}>
          {STATUS_OPTIONS.map(([v, label]) => (
            <option key={v} value={v}>
              {label}
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

function Row({ r }: { r: EnrollmentSummary }) {
  const href = `/enrollments/${encodeURIComponent(r.enrollment_id)}`
  function open(e: MouseEvent<HTMLAnchorElement>) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    e.preventDefault()
    sessionStorage.setItem(LAST_ROW_KEY, r.enrollment_id)
    navigate(href, { state: { fromList: true } })
  }
  const st = ENROLLMENT_STATUS[r.status]
  return (
    <tr>
      <td data-label="旅程">
        <a href={href} data-eid={r.enrollment_id} onClick={open}>
          {r.journey_name}（v{r.journey_version}）
        </a>
        <br />
        <span className="mono muted">{r.enrollment_id}</span>
      </td>
      <td data-label="狀態">
        <Badge tone={st.tone}>{st.label}</Badge>
      </td>
      <td data-label="目前節點">
        {r.current_node_name ?? r.current_node_type}
        <br />
        {r.current_node_name ? (
          <span className="mono muted">{r.current_node_type}</span>
        ) : (
          <span className="muted">（未命名節點，以型別顯示）</span>
        )}
      </td>
      <td data-label="進入時間">
        <Time iso={r.enrolled_at} />
      </td>
      <td data-label="最後更新">
        <Time iso={r.updated_at} />
      </td>
    </tr>
  )
}
