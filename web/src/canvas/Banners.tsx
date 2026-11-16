// 畫布上方的提示條（一次一條，role=alert）；編輯區保持原樣。
import { useState } from 'react'
import { AppLink } from '../components/AppLink'
import type { Def, Layout } from './model'
import type { Banner } from './useDraftEditor'

const pad = (n: number) => String(n).padStart(2, '0')
/** 今天／昨天 HH:mm → 同年 MM-DD HH:mm → 跨年 YYYY-MM-DD HH:mm（UI 規格「時間格式」） */
export function friendlyTime(iso: string | null | undefined, now = new Date()): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diff = Math.round((day(now) - day(d)) / 86400000)
  if (diff === 0) return `今天 ${hm}`
  if (diff === 1) return `昨天 ${hm}`
  const md = `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  return d.getFullYear() === now.getFullYear() ? `${md} ${hm}` : `${d.getFullYear()}-${md} ${hm}`
}

function CopyMine({ def, layout }: { def: Def; layout: Layout | null }) {
  const [done, setDone] = useState(false)
  return (
    <button
      type="button"
      className="btn secondary sm"
      onClick={() => {
        void navigator.clipboard?.writeText(JSON.stringify({ definition: def, layout }, null, 2)).then(
          () => setDone(true),
          () => undefined,
        )
      }}
    >
      {done ? '已複製我的修改' : '複製我的修改'}
    </button>
  )
}

export function BannerBar({
  banner,
  journeyId,
  def,
  layout,
  onLoadLatest,
  onWithdraw,
  onRetry,
  onShowReport,
}: {
  banner: Banner
  journeyId: string
  def: Def
  layout: Layout | null
  onLoadLatest: () => void
  onWithdraw: () => void
  onRetry: () => void
  onShowReport: () => void
}) {
  const mine = <CopyMine def={def} layout={layout} />
  switch (banner.kind) {
    case 'conflict':
      return (
        <div className="msg warn banner" role="alert">
          <div>
            <p>
              <strong>草稿已被 {banner.by ? banner.by.display_name : '其他人'}{banner.at ? ` 在 ${friendlyTime(banner.at)} ` : ' '}修改</strong>
            </p>
            <p>你開啟的是舊內容，現在儲存會蓋掉他的修改。</p>
          </div>
          <div className="acts">
            <button type="button" className="btn secondary sm" onClick={onLoadLatest}>
              載入最新草稿
            </button>
            {mine}
          </div>
        </div>
      )
    case 'inReview':
      return (
        <div className="msg info banner" role="alert">
          <div>
            <p>
              <strong>這份草稿已送審，不能修改</strong>
            </p>
            <p>要修改請先撤回送審。</p>
          </div>
          <div className="acts">
            <button type="button" className="btn secondary sm" onClick={onWithdraw}>
              撤回送審
            </button>
          </div>
        </div>
      )
    case 'aborted': {
      const j = banner.detail
      return (
        <div className="msg error banner" role="alert">
          <div>
            <p>
              <strong>這個旅程已停止，不能再修改或發佈</strong>
            </p>
            <p>
              {j?.stopped_at ? `停止者：${j.stopped_by?.display_name ?? '管理金鑰'}，${friendlyTime(j.stopped_at)}。` : ''}你的未儲存修改無法送出。
            </p>
          </div>
          <div className="acts">
            <AppLink className="btn secondary sm" to={`/journeys/${encodeURIComponent(journeyId)}`}>
              查看旅程
            </AppLink>
            {mine}
          </div>
        </div>
      )
    }
    case 'saveFailed':
    case 'submitFailed':
      return (
        <div className="msg error banner" role="alert">
          <div>
            <p>
              <strong>{banner.kind === 'saveFailed' ? '儲存失敗，修改仍保留在畫面上' : '送審失敗，請稍後再試'}</strong>
            </p>
            <p>
              {banner.requestId ? `請求編號 ${banner.requestId}；` : ''}連線恢復後會自動重試，也可以手動重試。
            </p>
          </div>
          <div className="acts">
            <button type="button" className="btn secondary sm" onClick={onRetry}>
              重試
            </button>
          </div>
        </div>
      )
    case 'submitBlocked':
      return (
        <div className="msg error banner" role="alert">
          <div>
            <p>
              <strong>送審前驗證沒有通過</strong>
            </p>
            <p>有 {banner.count} 個錯誤，已列在右側「驗證結果」。</p>
          </div>
          <div className="acts">
            <button type="button" className="btn secondary sm" onClick={onShowReport}>
              看驗證結果
            </button>
          </div>
        </div>
      )
  }
}
