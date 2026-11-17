import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, getJourney, getJourneyVersion, getNodeStats, putDraft } from '../api/client'
import { useAuth } from '../auth'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { Badge, RequestId, Skeleton, ErrorBox, Time } from '../components/ui'
import type { JourneyDetail, NodeStats } from '../api/types'
import { formatLocal, JOURNEY_STATUS } from '../format'
import { GraphSection } from '../graph/GraphSection'
import { navigate, type Loc } from '../router'
import { useFetch } from '../useFetch'
import { ActionButtons } from './review/ActionButtons'
import { ActionDialogs, type OpenDialog } from './review/ActionDialogs'
import { journeyActions, type ActionKey } from './review/model'

function LifecycleNotice({ detail }: { detail: JourneyDetail }) {
  const rv = detail.latest_review
  if (detail.status === 'ABORTED') {
    return (
      <div className="msg error notice" role="status">
        <p>
          <strong>這個旅程已停止，不能再修改或發佈</strong>
        </p>
        <p>
          {detail.stopped_by?.display_name ?? '管理金鑰'}
          {detail.stopped_at && (
            <>
              {' '}
              <Time iso={detail.stopped_at} />
            </>
          )}{' '}
          停止{detail.stop_reason ? `，原因「${detail.stop_reason}」` : ''}。進行中的客戶會陸續轉為已中止。
        </p>
      </div>
    )
  }
  if (detail.review_status === 'PENDING') {
    return (
      <div className="msg info notice" role="status">
        <p>
          <strong>已送審，等待管理員審核</strong>
        </p>
        <p>草稿已鎖定，不能修改。要改內容請先撤回送審。</p>
      </div>
    )
  }
  if (detail.review_status === 'REJECTED' && rv) {
    return (
      <div className="msg warn notice" role="status">
        <p>
          <strong>{rv.decided_by?.display_name ?? '管理員'}</strong>
          {rv.decided_at && (
            <>
              {' '}
              <Time iso={rv.decided_at} />
            </>
          )}{' '}
          退回{rv.reason ? `「${rv.reason}」` : ''}。草稿已解鎖，修改後可再送審。
        </p>
      </div>
    )
  }
  return null
}

type StatsFetch = ReturnType<typeof useFetch<NodeStats>>

/** 節點人數的狀態列：載入中、失敗（圖照常）、尚無客戶、資料時間與重新整理 */
function StatsBar({ stats }: { stats: StatsFetch }) {
  const shown = stats.data ?? stats.last
  const items = shown && Array.isArray(shown.items) ? shown.items : []
  const empty = items.length > 0 && items.every((x) => x.entered === 0)
  return (
    <div className="stats-bar" aria-busy={stats.loading && !shown ? 'true' : undefined}>
      {stats.loading && !shown && (
        <p className="muted" role="status">
          載入各節點人數…
        </p>
      )}
      {stats.error && (
        <div className="msg warn" role="alert">
          <p>
            <strong>各節點人數暫時取不到。</strong>旅程圖與節點設定不受影響。 <RequestId error={stats.error} />{' '}
            <button className="btn secondary sm" type="button" onClick={stats.reload}>
              重試
            </button>
          </p>
        </div>
      )}
      {stats.data && (
        <p className="muted">
          資料時間 {formatLocal(stats.data.as_of)}・重新整理才更新{' '}
          <button className="btn secondary sm" type="button" onClick={stats.reload}>
            重新整理
          </button>
        </p>
      )}
      {stats.data && empty && (
        <p className="msg info" role="status">
          這個版本還沒有客戶進入。有客戶進入後，這裡會顯示每個節點的人數。
        </p>
      )}
    </div>
  )
}

/** 旅程頁（D-062；v0.3.0 起有編輯、審核、停止動作）：標頭、版本下拉、節點圖（不疊軌跡） */
export function JourneyPage({ id, loc }: { id: string; loc: Loc }) {
  const load = useCallback(() => getJourney(id), [id])
  const { data, last, error, reload } = useFetch(load)
  const { user } = useAuth()
  const [open, setOpen] = useState<OpenDialog>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<ApiError | null>(null)
  // 重新整理時保留畫面（對話框與焦點不被骨架取代）
  const detail = data ?? (last && last.journey_id === id ? last : undefined)

  const vp = Number(new URLSearchParams(loc.search).get('version'))
  const requested = Number.isInteger(vp) && vp > 0 ? vp : null
  const latest = detail?.latest_version ?? null
  const version = requested ?? latest

  const hasDetail = !!detail
  const loadDef = useMemo(
    () => (hasDetail && version !== null ? () => getJourneyVersion(id, version) : null),
    [hasDetail, id, version],
  )
  const def = useFetch(loadDef)
  // 節點人數與圖各自載入、互不阻擋；沒有版本（尚未發佈）不打
  const loadStats = useMemo(
    () => (hasDetail && version !== null && detail && detail.versions.length > 0 ? () => getNodeStats(id, version) : null),
    [hasDetail, id, version, detail],
  )
  const stats = useFetch(loadStats)

  const h1 = useRef<HTMLHeadingElement>(null)
  const focused = useRef<string | null>(null)
  useEffect(() => {
    if (detail && focused.current !== detail.journey_id) {
      focused.current = detail.journey_id
      h1.current?.focus()
    }
  }, [detail])

  const reloadAll = useCallback(() => {
    setOpen(null)
    reload()
  }, [reload])

  async function startEdit() {
    if (!detail || busy) return
    const editUrl = `/journeys/${encodeURIComponent(id)}/edit`
    if (detail.has_draft || detail.latest_version === null) {
      navigate(editUrl)
      return
    }
    // 沒有草稿：以最新版本為底建立（契約 §3 PUT，revision 帶 0）
    setBusy(true)
    setActionError(null)
    try {
      const v = await getJourneyVersion(id, detail.latest_version)
      await putDraft(id, {
        revision: 0,
        definition: { name: v.name, description: v.description, settings: v.settings, entry_node: v.entry_node, nodes: v.nodes },
        layout: v.layout,
      })
      navigate(editUrl)
    } catch (e) {
      setActionError(e instanceof ApiError ? e : new ApiError(0, null, null))
      setBusy(false)
    }
  }

  function act(key: ActionKey) {
    if (key === 'edit') void startEdit()
    else if (key === 'resubmit') navigate(`/journeys/${encodeURIComponent(id)}/edit`)
    else setOpen(key)
  }

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
  const statItems = stats.data && Array.isArray(stats.data.items) ? stats.data.items : undefined
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
            {detail.review_status === 'PENDING' && (
              <>
                {' '}
                <Badge tone="info">審核中</Badge>
              </>
            )}
            {detail.review_status === 'REJECTED' && (
              <>
                {' '}
                <Badge tone="warning">已退回</Badge>
              </>
            )}
          </h1>
          <p className="muted">
            {latest !== null ? `最新版本 v${latest}` : '尚未發佈'}
            {detail.published_at && (
              <>
                ・最近發佈 <Time iso={detail.published_at} />
              </>
            )}
            ・最後更新 <Time iso={detail.updated_at} />
            {detail.review_status === 'PENDING' && detail.latest_review && (
              <>
                ・草稿 v{(latest ?? 0) + 1}・{detail.latest_review.submitted_by?.display_name ?? '管理金鑰'}{' '}
                <Time iso={detail.latest_review.submitted_at} /> 送審
              </>
            )}
          </p>
        </div>
        <div className="head-act">
          <ActionButtons specs={journeyActions(detail, user)} busy={busy} onAct={act} />
          {detail.review_status === 'PENDING' && (
            <AppLink className="btn secondary" to={`/journeys/${encodeURIComponent(id)}/review`}>
              看送審內容
            </AppLink>
          )}
          <AppLink className="btn secondary" to={`/journeys/${encodeURIComponent(id)}/history`}>
            審核紀錄
          </AppLink>
          <AppLink to={`/enrollments?journey_id=${encodeURIComponent(id)}`}>旅程進度</AppLink>
        </div>
      </div>
      {actionError && (
        <div className="msg error" role="alert">
          <p>{actionError.status === 409 ? '這個旅程目前不能編輯，請重新載入後再試。' : '暫時無法建立草稿，請稍後再試。'}</p>
        </div>
      )}
      <LifecycleNotice detail={detail} />
      <ActionDialogs
        open={open}
        detail={detail}
        review={detail.latest_review ?? null}
        onClose={() => setOpen(null)}
        onReload={reloadAll}
        onDone={reloadAll}
      />
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
              <StatsBar stats={stats} />
              <GraphSection
                def={def.data}
                overlay={null}
                label="旅程圖（可橫向捲動；文字版見下方清單）"
                stats={statItems}
                panel
              />
            </>
          )}
        </>
      )}
    </>
  )
}
