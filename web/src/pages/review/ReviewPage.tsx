// 管理員審核頁（rv-review）：送審資訊、跟前一版比的變更摘要、核准或退回
import { useCallback, useMemo, useState } from 'react'
import { ApiError, getDraft, getJourney, getJourneyVersion } from '../../api/client'
import { useAuth, ROLE_LABEL } from '../../auth'
import type { Draft, JourneyDetail, JourneyVersion, Review } from '../../api/types'
import { AppLink } from '../../components/AppLink'
import { Crumbs } from '../../components/Shell'
import { Badge, ErrorBox, Time } from '../../components/ui'
import { JOURNEY_STATUS } from '../../format'
import { navigate } from '../../router'
import { useFetch } from '../../useFetch'
import { ActionButtons } from './ActionButtons'
import { ActionDialogs, type OpenDialog } from './ActionDialogs'
import { CHANGE_LABEL, diffNodes, reviewPageActions, type ActionKey } from './model'

interface Loaded {
  detail: JourneyDetail
  draft: Draft | null
  review: Review | null
  base: JourneyVersion | null
}

async function loadReview(id: string): Promise<Loaded> {
  const detail = await getJourney(id)
  if (detail.review_status !== 'PENDING') return { detail, draft: null, review: null, base: null }
  let draft: Draft
  try {
    draft = await getDraft(id)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404 && e.code === 'DRAFT_NOT_FOUND') return { detail, draft: null, review: null, base: null }
    throw e
  }
  const review = draft.latest_review?.status === 'PENDING' ? draft.latest_review : null
  const baseNo = review?.base_version ?? null
  const base = baseNo !== null ? await getJourneyVersion(id, baseNo) : null
  return { detail, draft, review, base }
}

export function ReviewPage({ id }: { id: string }) {
  const { user } = useAuth()
  const load = useCallback(() => loadReview(id), [id])
  const { data, error, loading, reload } = useFetch(load)
  const [open, setOpen] = useState<OpenDialog>(null)

  const changes = useMemo(() => (data?.draft ? diffNodes(data.base, data.draft.definition) : []), [data])

  const crumbs = (last: string) => <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: last }]} />
  const back = (
    <AppLink className="btn secondary" to="/journeys">
      回旅程列表
    </AppLink>
  )

  if (loading) {
    return (
      <>
        {crumbs('審核')}
        <div className="card" role="status" aria-label="載入審核內容中" aria-busy="true">
          <span className="skel" style={{ width: '40%' }} />
          <span className="skel" />
          <span className="skel" style={{ width: '70%' }} />
        </div>
      </>
    )
  }
  if (error?.status === 404) {
    return (
      <>
        {crumbs('找不到')}
        <div className="card msg">
          <h1 className="h1-card">找不到這個旅程。</h1>
          <p>可能已被刪除，或網址有誤。</p>
          <p>{back}</p>
        </div>
      </>
    )
  }
  if (error || !data) {
    return (
      <>
        {crumbs('審核')}
        {error && <ErrorBox title="暫時無法載入這次送審的內容。" error={error} onRetry={reload} />}
      </>
    )
  }

  const { detail, review, draft } = data
  const dj = encodeURIComponent(id)
  if (!review || !draft) {
    return (
      <>
        <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: detail.name, to: `/journeys/${dj}` }, { label: '審核' }]} />
        <div className="card msg">
          <h1 className="h1-card">這個旅程目前沒有待審核的送審。</h1>
          <p>可能已被撤回、核准或退回。</p>
          <p>
            <AppLink className="btn secondary" to={`/journeys/${dj}/history`}>
              看審核紀錄
            </AppLink>
          </p>
        </div>
      </>
    )
  }

  const next = (detail.latest_version ?? 0) + 1
  const st = JOURNEY_STATUS[detail.status] ?? { label: detail.status, tone: 'neutral' as const }
  const specs = reviewPageActions(review, user)
  const count = (c: 'added' | 'modified' | 'removed') => changes.filter((x) => x.change === c).length
  const by = review.submitted_by?.display_name ?? '管理金鑰'
  const base = review.base_version
  const act = (k: ActionKey) => setOpen(k === 'approve' ? 'approve' : 'reject')
  const done = () => {
    setOpen(null)
    navigate(`/journeys/${dj}`)
  }
  const reloadAll = () => {
    setOpen(null)
    reload()
  }

  return (
    <>
      <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: detail.name, to: `/journeys/${dj}` }, { label: `審核 v${next}` }]} />
      <div className="pagehead">
        <div>
          <h1>{detail.name}：審核 v{next}</h1>
          <p className="muted">
            {detail.latest_version !== null && (
              <>
                <Badge tone={st.tone}>{st.label}</Badge> 目前上線 v{detail.latest_version}{' '}
              </>
            )}
            <Badge tone="info">審核中</Badge>
          </p>
          <p className="muted">
            {by} <Time iso={review.submitted_at} /> 送審{base !== null ? `・基於 v${base}` : ''}
          </p>
        </div>
        <div className="head-act">
          <ActionButtons specs={specs} onAct={act} />
        </div>
      </div>
      <div className="card">
        <p style={{ margin: 0 }}>
          {review.note ? <>說明：「{review.note}」</> : '沒有送審說明。'}
          {base !== null ? `核准前上線的仍是 v${base}。` : ''}
        </p>
      </div>
      {user.role !== 'ADMIN' && (
        <div className="msg info notice">
          <p>
            <strong>只有管理員可以核准或退回</strong>
          </p>
          <p>你的角色是{ROLE_LABEL[user.role]}，可以查看送審內容；若是你送的，可在旅程頁撤回送審。</p>
        </div>
      )}
      <div className="card">
        <h2 className="sec">{base !== null ? `跟 v${base} 比，這次改了什麼` : '這次送審的內容'}</h2>
        <p>
          新增 {count('added')} 個節點・修改 {count('modified')} 個節點・刪除 {count('removed')} 個節點
        </p>
        {changes.length > 0 ? (
          <div className="scroll">
            <table className="stack">
              <caption>節點變更明細</caption>
              <thead>
                <tr>
                  <th scope="col">節點</th>
                  <th scope="col">變更</th>
                  <th scope="col">{base !== null ? `v${base}` : '之前'}</th>
                  <th scope="col">v{next}（這次送審）</th>
                </tr>
              </thead>
              <tbody>
                {changes.map((c) => (
                  <tr key={c.id}>
                    <td data-label="節點">{c.name}</td>
                    <td data-label="變更">
                      <span className={`chg chg-${c.change}`}>{CHANGE_LABEL[c.change]}</span>
                    </td>
                    <td data-label="之前">{c.before ?? '—'}</td>
                    <td data-label="這次送審">{c.after ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="muted">節點沒有變更（可能只改了旅程設定或名稱）。</p>
        )}
      </div>
      <ActionDialogs open={open} detail={detail} review={review} onClose={() => setOpen(null)} onDone={done} onReload={reloadAll} />
    </>
  )
}
