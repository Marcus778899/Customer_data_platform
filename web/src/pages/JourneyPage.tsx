import { useCallback, useEffect, useMemo, useRef } from 'react'
import { getJourney, getJourneyVersion } from '../api/client'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox, Skeleton, Time } from '../components/ui'
import { formatLocal, JOURNEY_STATUS } from '../format'
import { GraphSection } from '../graph/GraphSection'
import { navigate, type Loc } from '../router'
import { useFetch } from '../useFetch'

/** 旅程頁（唯讀，D-062）：標頭、版本下拉、節點圖（不疊軌跡） */
export function JourneyPage({ id, loc }: { id: string; loc: Loc }) {
  const load = useCallback(() => getJourney(id), [id])
  const { data: detail, error, reload } = useFetch(load)

  const vp = Number(new URLSearchParams(loc.search).get('version'))
  const requested = Number.isInteger(vp) && vp > 0 ? vp : null
  const latest = detail?.latest_version ?? null
  const version = requested ?? latest

  const loadDef = useMemo(
    () => (detail && version !== null ? () => getJourneyVersion(id, version) : null),
    [detail, id, version],
  )
  const def = useFetch(loadDef)

  const h1 = useRef<HTMLHeadingElement>(null)
  const focused = useRef<string | null>(null)
  useEffect(() => {
    if (detail && focused.current !== detail.journey_id) {
      focused.current = detail.journey_id
      h1.current?.focus()
    }
  }, [detail])

  const crumbs = (last: string) => <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: last }]} />

  if (error?.status === 404) {
    return (
      <>
        {crumbs('找不到')}
        <div className="card msg" role="alert">
          <h1 className="h1-card">找不到這個旅程</h1>
          <p>它可能不存在，或網址有誤。</p>
          <p>
            <AppLink className="btn secondary" to="/journeys">
              回旅程列表
            </AppLink>
          </p>
        </div>
      </>
    )
  }
  if (error) {
    return (
      <>
        {crumbs('旅程')}
        <ErrorBox title="無法載入旅程。" error={error} onRetry={reload} />
      </>
    )
  }
  if (!detail) {
    return (
      <>
        {crumbs('載入中…')}
        <h1>載入中…</h1>
        <Skeleton lines={4} />
      </>
    )
  }

  const st = JOURNEY_STATUS[detail.status] ?? { label: detail.status, tone: 'neutral' as const }
  const old = version !== null && latest !== null && version !== latest
  const selectVersion = (v: number) =>
    navigate(`/journeys/${encodeURIComponent(id)}${v === latest ? '' : `?version=${v}`}`, { replace: true })

  return (
    <>
      {crumbs(detail.name)}
      <div className="pagehead">
        <div>
          <h1 tabIndex={-1} ref={h1}>
            {detail.name} <Badge tone={st.tone}>{st.label}</Badge>
          </h1>
          <p className="muted">
            {latest !== null ? `最新版本 v${latest}` : '尚未發佈'}
            {detail.published_at && (
              <>
                ・最近發佈 <Time iso={detail.published_at} />
              </>
            )}
            ・最後更新 <Time iso={detail.updated_at} />
          </p>
        </div>
        <AppLink to={`/enrollments?journey_id=${encodeURIComponent(id)}`}>這個旅程的 Enrollment</AppLink>
      </div>
      {detail.description && (
        <div className="card">
          <h2 className="sec">說明</h2>
          <p style={{ margin: 0 }}>{detail.description}</p>
        </div>
      )}
      {detail.versions.length === 0 ? (
        <p className="msg muted card">這個旅程尚未發佈任何版本，沒有流程圖可看。</p>
      ) : (
        <>
          <div className="field" style={{ marginTop: 'var(--space-4)', maxWidth: 360 }}>
            <label htmlFor="ver">版本</label>
            <select id="ver" value={version ?? ''} onChange={(e) => selectVersion(Number(e.target.value))}>
              {detail.versions.map((v) => (
                <option key={v.version} value={v.version}>
                  v{v.version}
                  {v.version === latest ? '（最新）' : ''}・{formatLocal(v.published_at)}
                </option>
              ))}
            </select>
            <span className="hint">切換後只更新下方的圖；已發佈的版本不可修改。</span>
          </div>
          {old && (
            <div className="msg warn" role="status" style={{ marginBottom: 'var(--space-3)' }}>
              <p>
                你正在看 v{version}，不是最新版本。已發佈的版本不可修改；新進入的客戶使用最新版本 v{latest}。{' '}
                <AppLink to={`/journeys/${encodeURIComponent(id)}`}>回到最新版本</AppLink>
              </p>
            </div>
          )}
          {def.loading && <Skeleton lines={4} />}
          {def.error && (
            <ErrorBox
              title={def.error.status === 404 ? '找不到這個版本。' : '無法載入流程圖。'}
              error={def.error}
              onRetry={def.error.status === 404 ? undefined : def.reload}
            />
          )}
          {def.data && Array.isArray(def.data.nodes) && (
            <>
              <GraphSection def={def.data} overlay={null} label="旅程圖（可橫向捲動；文字版見下方清單）" />
              <p className="muted">
                v0.2.0 的旅程頁唯讀：沒有編輯、停止、審核按鈕；節點只顯示名稱與類型，設定值 v0.3.0 才提供。
              </p>
            </>
          )}
        </>
      )}
    </>
  )
}
