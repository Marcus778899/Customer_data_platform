// 旅程頁與審核頁共用：依 open 開對應對話框、呼叫 API
import { approveReview, deleteDraft, rejectReview, stopJourney, withdrawReview } from '../../api/client'
import type { JourneyDetail, ReviewBrief } from '../../api/types'
import { ApproveDialog, DiscardDialog, RejectDialog, StopDialog, WithdrawDialog } from './Dialogs'

export type OpenDialog = 'approve' | 'reject' | 'withdraw' | 'stop' | 'discard' | null

export function ActionDialogs({
  open,
  detail,
  review,
  onClose,
  onDone,
  onReload,
}: {
  open: OpenDialog
  detail: JourneyDetail
  review: ReviewBrief | null
  onClose: () => void
  /** 動作成功：呼叫端關閉對話框並更新畫面 */
  onDone: (kind: Exclude<OpenDialog, null>) => void
  onReload: () => void
}) {
  if (!open) return null
  const id = detail.journey_id
  const rid = review?.review_id ?? ''
  const common = { onClose, onReload, onDone: () => onDone(open) }
  switch (open) {
    case 'approve':
      return (
        <ApproveDialog
          {...common}
          version={(detail.latest_version ?? 0) + 1}
          publishedVersion={detail.latest_version}
          run={(note) => approveReview(id, rid, note)}
        />
      )
    case 'reject':
      return (
        <RejectDialog
          {...common}
          submitter={review?.submitted_by?.display_name ?? '送審者'}
          submittedAt={review?.submitted_at ?? null}
          run={(reason) => rejectReview(id, rid, reason)}
        />
      )
    case 'withdraw':
      return <WithdrawDialog {...common} run={(reason) => withdrawReview(id, rid, reason)} />
    case 'stop':
      return (
        <StopDialog
          {...common}
          journeyName={detail.name}
          hasPending={detail.review_status === 'PENDING'}
          run={(reason) => stopJourney(id, reason)}
        />
      )
    case 'discard':
      return <DiscardDialog {...common} run={() => deleteDraft(id)} />
  }
}
