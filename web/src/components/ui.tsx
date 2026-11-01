import { createContext, useContext, useState, type ReactNode } from 'react'
import { formatLocal, type Tone } from '../format'
import { ApiError } from '../api/client'

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`badge b-${tone}`}>{children}</span>
}

/** true = 每個時間旁邊顯示 UTC 原值（D-016） */
export const UtcContext = createContext(false)

/** 瀏覽器本地時區顯示；UTC 原值放 dateTime／title，並可由頁面開關顯示為可見文字 */
export function Time({ iso, seconds = false }: { iso: string; seconds?: boolean }) {
  const showUtc = useContext(UtcContext)
  return (
    <>
      <time dateTime={iso} title={iso}>
        {formatLocal(iso, seconds)}
      </time>
      {showUtc && <span className="muted mono">（UTC {iso}）</span>}
    </>
  )
}

export function RequestId({ error }: { error: ApiError }) {
  if (!error.requestId) return null
  return (
    <>
      若持續發生，請回報請求代碼 <span className="mono">{error.requestId}</span>。
    </>
  )
}

/** 5xx／連線失敗的共用錯誤框（role=alert）；onRetry 有給才顯示「重試」 */
export function ErrorBox({ title, error, onRetry }: { title: string; error: ApiError; onRetry?: () => void }) {
  if (error.status === 403) {
    // 沒有權限：重試無意義，不提供按鈕
    return (
      <div className="msg error" role="alert">
        <p>
          <strong>{title}</strong>
        </p>
        <p>
          你的帳號沒有權限執行這項操作。
          <RequestId error={error} />
        </p>
      </div>
    )
  }
  return (
    <div className="msg error" role="alert">
      <p>
        <strong>{title}</strong>
      </p>
      <p>
        {error.status === 400 ? '查詢條件有誤。' : '伺服器暫時無法回應，請稍後再試。'}
        <RequestId error={error} />
      </p>
      {onRetry && (
        <button className="btn secondary" type="button" onClick={onRetry}>
          重試
        </button>
      )}
    </div>
  )
}

export function Loading({ children }: { children: ReactNode }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true" className="card">
      <span className="muted">{children}</span>
      <span className="skel" style={{ width: '40%' }} />
      <span className="skel" />
      <span className="skel" style={{ width: '70%' }} />
    </div>
  )
}

/** 骨架列：role=status、讀屏讀「載入中」 */
export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="card" role="status" aria-busy="true">
      <span className="sr">載入中</span>
      {Array.from({ length: lines }, (_, i) => (
        <span className="skel" key={i} />
      ))}
    </div>
  )
}

/** cursor 分頁的「載入更多」；失敗時改顯示錯誤與重試 */
export function MoreControls({
  next,
  busy,
  error,
  onMore,
  label = '載入更多',
}: {
  next: string | null
  busy: boolean
  error: ApiError | null
  onMore: () => void
  label?: string
}) {
  if (error) {
    return (
      <div style={{ marginTop: 'var(--space-3)' }}>
        <ErrorBox title="載入更多失敗" error={error} onRetry={onMore} />
      </div>
    )
  }
  if (!next) return null
  return (
    <p>
      <button className="btn secondary" type="button" onClick={onMore} aria-disabled={busy}>
        {busy ? '載入中…' : label}
      </button>
    </p>
  )
}

/** 複製完整值（只用於未遮罩的 ID） */
export function CopyButton({ text, label }: { text: string; label: string }) {
  const [done, setDone] = useState(false)
  function copy() {
    void navigator.clipboard?.writeText(text).then(() => setDone(true), () => undefined)
  }
  return (
    <button className="btn secondary sm" type="button" aria-label={label} onClick={copy}>
      {done ? '已複製' : '複製'}
    </button>
  )
}
