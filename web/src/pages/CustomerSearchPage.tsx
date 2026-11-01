import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { searchProfiles } from '../api/client'
import type { ProfileSummary } from '../api/types'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox, Skeleton, Time } from '../components/ui'
import { identifierLabel, shortId } from '../format'
import { navigate, type Loc } from '../router'
import { useFetch } from '../useFetch'

/** 客戶搜尋（D-048）：完全比對；單筆直進客戶頁，多筆列表最多 20 */
export function CustomerSearchPage({ loc }: { loc: Loc }) {
  const q = (new URLSearchParams(loc.search).get('q') ?? '').trim()
  const load = useCallback(() => searchProfiles(q), [q])
  const { data, error, loading, reload } = useFetch(q ? load : null)

  const focusResults = useRef(false)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [emptyInput, setEmptyInput] = useState(false)

  // 只有一位客戶：直接進客戶頁（取代這一筆歷史，上一頁不會再被彈回去）
  const only = data && data.items.length === 1 ? data.items[0] : null
  useEffect(() => {
    if (only) {
      navigate(`/customers/${encodeURIComponent(only.profile_id)}`, {
        replace: true,
        state: { profileId: only.profile_id, q, matched_by: only.matched_by },
      })
    }
  }, [only, q])

  // 結果出現後焦點到結果標題（layout effect：與 DOM 更新同一時間點）
  const shown = data && data.items.length > 1
  useLayoutEffect(() => {
    if (shown && focusResults.current) {
      focusResults.current = false
      headingRef.current?.focus()
    }
  }, [shown, data])

  function submit(value: string) {
    const v = value.trim()
    if (!v) {
      setEmptyInput(true)
      return
    }
    setEmptyInput(false)
    focusResults.current = true
    if (v === q) reload()
    else navigate(`/customers?q=${encodeURIComponent(v)}`)
  }

  const invalid = emptyInput || error?.status === 400

  return (
    <>
      <Crumbs items={[{ label: '客戶', to: '/customers' }, { label: '搜尋' }]} />
      <h1>客戶</h1>
      <SearchForm key={q} initial={q} busy={loading} invalid={invalid} onSubmit={submit} />
      <div style={{ marginTop: 'var(--space-4)' }}>
        {!q && !emptyInput && <Hints />}
        {q && loading && <Skeleton lines={2} />}
        {error && error.status !== 400 && <ErrorBox title="搜尋失敗。" error={error} onRetry={reload} />}
        {data && data.items.length === 0 && (
          <div className="card msg" role="status">
            <h2 className="sec">找不到符合的客戶</h2>
            <p>可能原因：輸入內容不完整或有錯字、客戶尚未送過任何事件、識別碼尚未綁定到客戶。</p>
          </div>
        )}
        {shown && <Results items={data.items} headingRef={headingRef} />}
      </div>
    </>
  )
}

function SearchForm({
  initial,
  busy,
  invalid,
  onSubmit,
}: {
  initial: string
  busy: boolean
  invalid: boolean
  onSubmit: (v: string) => void
}) {
  const [value, setValue] = useState(initial)
  const id = useId()
  function handle(e: FormEvent) {
    e.preventDefault()
    if (!busy) onSubmit(value)
  }
  return (
    <form className="search-big" role="search" onSubmit={handle}>
      <div className="field">
        <label htmlFor={`${id}-q`}>搜尋客戶</label>
        <input
          id={`${id}-q`}
          type="search"
          value={value}
          placeholder="email、手機、外部 ID 或客戶 ID"
          aria-describedby={invalid ? `${id}-h ${id}-e` : `${id}-h`}
          aria-invalid={invalid || undefined}
          onChange={(e) => setValue(e.target.value)}
        />
        <span className="hint" id={`${id}-h`}>
          輸入完整內容；手機可含空格與破折號。只做完全比對，不支援輸入一部分（D-048）。
        </span>
        {invalid && (
          <span className="err-text" id={`${id}-e`}>
            請輸入要搜尋的內容。
          </span>
        )}
      </div>
      <button className="btn" type="submit" aria-disabled={busy}>
        {busy ? '搜尋中…' : '搜尋'}
      </button>
    </form>
  )
}

function Hints() {
  return (
    <div className="card">
      <h2 className="sec">可以輸入什麼</h2>
      <dl className="kv">
        <dt>email</dt>
        <dd>
          <span className="mono">name@example.com</span>（不分大小寫）
        </dd>
        <dt>手機</dt>
        <dd>
          <span className="mono">0912-345-678</span> 或 <span className="mono">+886912345678</span>
        </dd>
        <dt>外部 ID</dt>
        <dd>會員編號、LINE 使用者 ID、裝置 ID</dd>
        <dt>客戶 ID</dt>
        <dd>平台產生的 26 碼 ID</dd>
      </dl>
      <p className="muted">只做完全比對，不支援輸入一部分。</p>
    </div>
  )
}

function Results({
  items,
  headingRef,
}: {
  items: ProfileSummary[]
  headingRef: React.RefObject<HTMLHeadingElement | null>
}) {
  return (
    <section>
      <h2 className="result-heading" tabIndex={-1} ref={headingRef}>
        搜尋結果
      </h2>
      <div className="card scroll">
        <table className="stack">
          <caption>找到 {items.length} 位客戶（最多顯示 20 筆）</caption>
          <thead>
            <tr>
              <th scope="col">比對方式</th>
              <th scope="col">識別碼</th>
              <th scope="col">客戶 ID</th>
              <th scope="col">最近更新</th>
            </tr>
          </thead>
          <tbody>
            {items.map((p) => (
              <tr key={p.profile_id}>
                <td data-label="比對方式">{identifierLabel(p.matched_by)}</td>
                <td data-label="識別碼">
                  {p.identifiers.map((i, k) => (
                    <div key={k}>
                      {identifierLabel(i.type)}：<span className={i.masked ? undefined : 'mono'}>{i.value}</span>{' '}
                      {i.masked && <Badge tone="neutral">已遮罩</Badge>}
                    </div>
                  ))}
                </td>
                <td data-label="客戶 ID">
                  <AppLink className="tap mono" to={`/customers/${encodeURIComponent(p.profile_id)}`} title={p.profile_id}>
                    {shortId(p.profile_id)}
                  </AppLink>
                </td>
                <td data-label="最近更新" className="nw">
                  <Time iso={p.updated_at} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted">
        同一位客戶只列一次；「比對方式」是命中的識別碼類型。長 ID 只顯示頭尾，完整值在客戶頁。個資依白名單遮罩。
      </p>
    </section>
  )
}
