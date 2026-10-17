import { createContext, useContext, type ReactNode } from 'react'
import { useAuth } from '../auth'
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

export function Topbar() {
  const { username, logout } = useAuth()
  return (
    <div className="topbar">
      <strong>客戶旅程軌跡</strong>
      <div className="who">
        <span className="muted">
          已登入：<span className="mono">{username}</span>
        </span>
        <button className="btn secondary" type="button" onClick={logout}>
          登出
        </button>
      </div>
    </div>
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
