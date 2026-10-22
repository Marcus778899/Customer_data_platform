import { useCallback, useEffect, useRef, useState } from 'react'
import { getProfile, listProfileEnrollments } from '../api/client'
import type { ProfileDetail } from '../api/types'
import { AppLink } from '../components/AppLink'
import { KeyValues } from '../components/KeyValues'
import { Crumbs } from '../components/Shell'
import { Badge, CopyButton, ErrorBox, MoreControls, Skeleton, Time, UtcContext } from '../components/ui'
import { ENROLLMENT_STATUS, identifierLabel, shortId } from '../format'
import { usePagedList } from '../hooks'
import { useFetch } from '../useFetch'

interface NavState {
  profileId?: string
  q?: string
  matched_by?: string
}

/** 客戶頁：左基本資料（識別碼、屬性、遮罩）、右參加過的旅程；兩個 API 分開處理 */
export function CustomerPage({ id }: { id: string }) {
  const load = useCallback(() => getProfile(id), [id])
  const { data, error, reload } = useFetch(load)
  const [showUtc, setShowUtc] = useState(false)

  const h1 = useRef<HTMLHeadingElement>(null)
  const focused = useRef<string | null>(null)
  useEffect(() => {
    if (data && focused.current !== data.profile_id) {
      focused.current = data.profile_id
      h1.current?.focus()
    }
  }, [data])

  const nav = window.history.state as NavState | null
  const direct = nav?.profileId === id && nav.q && nav.matched_by ? nav : null

  if (error?.status === 404) {
    return (
      <>
        <Crumbs items={[{ label: '客戶', to: '/customers' }, { label: '找不到' }]} />
        <div className="card msg" role="alert">
          <h1 className="h1-card">找不到這位客戶</h1>
          <p>客戶 ID 可能有誤。</p>
          <p>
            <AppLink className="btn secondary" to="/customers">
              回搜尋
            </AppLink>
          </p>
        </div>
      </>
    )
  }
  if (error) {
    return (
      <>
        <Crumbs items={[{ label: '客戶', to: '/customers' }, { label: '客戶' }]} />
        <ErrorBox title="無法載入客戶資料。" error={error} onRetry={reload} />
      </>
    )
  }

  const nameAttr = data?.attributes.name
  const useName =
    data && typeof nameAttr === 'string' && nameAttr !== '' && !data.masked_paths.includes('attributes.name')
  const crumbLast = !data ? '載入中…' : useName ? nameAttr : `客戶 ${shortId(id)}`

  return (
    <UtcContext.Provider value={showUtc}>
      <Crumbs items={[{ label: '客戶', to: '/customers' }, { label: crumbLast }]} />
      <div className="pagehead">
        <div>
          <h1 tabIndex={-1} ref={h1}>
            {!data ? (
              '載入中…'
            ) : useName ? (
              nameAttr
            ) : (
              <>
                客戶 <span className="mono">{shortId(id)}</span>
              </>
            )}
          </h1>
          {data && useName && (
            <p className="muted">
              客戶 ID <span className="mono">{shortId(id)}</span>
            </p>
          )}
        </div>
        {data && (
          <div className="row">
            <label className="check">
              <input type="checkbox" checked={showUtc} onChange={(e) => setShowUtc(e.target.checked)} />
              同時顯示 UTC 原值
            </label>
          </div>
        )}
      </div>
      {!data && <Skeleton lines={3} />}
      {data && (
        <>
          {direct && (
            <p className="msg info" role="status" style={{ margin: '0 0 var(--space-4)', textAlign: 'left' }}>
              搜尋「{direct.q}」比對到這位客戶（比對方式：{identifierLabel(direct.matched_by!)}）。{' '}
              <AppLink to="/customers">重新搜尋</AppLink>
            </p>
          )}
          <div className="cols two">
            <Basic p={data} />
            <Enrollments id={id} />
          </div>
        </>
      )}
    </UtcContext.Provider>
  )
}

function Basic({ p }: { p: ProfileDetail }) {
  const masked = new Set(p.masked_paths)
  const attrs = Object.keys(p.attributes).length
  const anyMask = masked.size > 0 || p.identifiers.some((i) => i.masked)
  return (
    <section className="card">
      <h2 className="sec">基本資料</h2>
      <dl className="kv">
        <dt>客戶 ID</dt>
        <dd>
          <span className="mono">{p.profile_id}</span> <CopyButton text={p.profile_id} label="複製客戶 ID" />
        </dd>
        <dt>建立時間</dt>
        <dd>
          <Time iso={p.created_at} />
        </dd>
        <dt>最近更新</dt>
        <dd>
          <Time iso={p.updated_at} />
        </dd>
        <dt>資料時間</dt>
        <dd>
          <Time iso={p.as_of} seconds />
        </dd>
      </dl>
      <div className="scroll">
        <table className="plain stack">
          <caption>識別碼</caption>
          <thead>
            <tr>
              <th scope="col">類型</th>
              <th scope="col">值</th>
            </tr>
          </thead>
          <tbody>
            {p.identifiers.map((i, k) => {
              const m = i.masked || masked.has(`identifiers[${k}].value`)
              return (
                <tr key={k}>
                  <td data-label="類型">{identifierLabel(i.type)}</td>
                  <td data-label="值" className={m ? undefined : 'mono'}>
                    {i.value} {m && <Badge tone="neutral">已遮罩</Badge>}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <h3 style={{ marginTop: 'var(--space-4)' }}>客戶屬性</h3>
      {attrs > 0 ? (
        <KeyValues data={p.attributes} basePath="attributes" masked={masked} />
      ) : (
        <p className="muted">沒有客戶屬性。</p>
      )}
      {anyMask && (
        <p className="mask-note">
          只有白名單的欄位顯示明文；遮罩值無法解除，也不提供複製。屬性 name 未遮罩時當頁面標題，否則以客戶 ID 為標題。
        </p>
      )}
    </section>
  )
}

function Enrollments({ id }: { id: string }) {
  const load = useCallback((cursor?: string) => listProfileEnrollments(id, cursor), [id])
  const { items, next, error, loading, reload, loadMore, moreBusy, moreError, data } = usePagedList(load)
  return (
    <section className="card">
      <h2 className="sec">參加過的旅程</h2>
      {loading && <Skeleton lines={3} />}
      {error && <ErrorBox title="無法載入旅程紀錄。" error={error} onRetry={reload} />}
      {data && items.length === 0 && (
        <div className="msg">
          <h3>還沒有參加任何旅程</h3>
          <p>客戶觸發旅程的入口事件後，會顯示在這裡。</p>
        </div>
      )}
      {data && items.length > 0 && (
        <>
          <div className="scroll">
            <table className="stack">
              <caption>{next ? `參加過的旅程，已載入 ${items.length} 筆` : `參加過 ${items.length} 個旅程`}，新到舊</caption>
              <thead>
                <tr>
                  <th scope="col">旅程（版本）</th>
                  <th scope="col">狀態</th>
                  <th scope="col">目前／最後節點</th>
                  <th scope="col">進入時間</th>
                  <th scope="col">結束時間</th>
                  <th scope="col">Enrollment ID</th>
                </tr>
              </thead>
              <tbody>
                {items.map((r) => {
                  const st = ENROLLMENT_STATUS[r.status]
                  return (
                    <tr key={r.enrollment_id}>
                      <td data-label="旅程" className="nw">
                        <AppLink className="tap" to={`/enrollments/${encodeURIComponent(r.enrollment_id)}`}>
                          {r.journey_name}（v{r.journey_version}）
                        </AppLink>
                      </td>
                      <td data-label="狀態">
                        <Badge tone={st.tone}>{st.label}</Badge>
                      </td>
                      <td data-label="目前／最後節點">{r.current_node_name ?? r.current_node_type}</td>
                      <td data-label="進入時間" className="nw">
                        <Time iso={r.enrolled_at} />
                      </td>
                      <td data-label="結束時間" className="nw">
                        {r.terminated_at ? <Time iso={r.terminated_at} /> : '—'}
                      </td>
                      <td data-label="Enrollment ID">
                        <span className="mono" title={r.enrollment_id}>
                          {shortId(r.enrollment_id)}
                        </span>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <MoreControls next={next} busy={moreBusy} error={moreError} onMore={loadMore} />
          <p className="muted">時間為瀏覽器時區（D-016）。點旅程名稱看圖形軌跡。</p>
        </>
      )}
    </section>
  )
}
