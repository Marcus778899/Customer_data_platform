import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, getMe, login } from '../api/client'
import type { CurrentUser } from '../api/types'
import { PRODUCT_NAME } from '../components/Shell'
import { RequestId } from '../components/ui'

interface Props {
  /** 因 session 逾時（API 回 401）被導回時為 true */
  expired: boolean
  onLoggedIn: (me: CurrentUser) => void
}

const ERROR_ID = 'login-error'
const EMAIL_ERR_ID = 'login-email-error'
const PASS_ERR_ID = 'login-password-error'

function countdownText(sec: number): string {
  return sec >= 60 ? `${Math.floor(sec / 60)} 分 ${sec % 60} 秒` : `${sec} 秒`
}

export function LoginPage({ expired, onLoggedIn }: Props) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [emptyEmail, setEmptyEmail] = useState(false)
  const [emptyPassword, setEmptyPassword] = useState(false)
  // 429（D-045）：until 為倒數終點；first 為開始時的剩餘秒數（讀屏只朗讀這一次）
  const [lock, setLock] = useState<{ until: number; first: number } | null>(null)
  const [remaining, setRemaining] = useState(0)
  const [ready, setReady] = useState(false)
  const emailRef = useRef<HTMLInputElement>(null)
  const passRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    emailRef.current?.focus()
  }, [])

  // 倒數：每 250ms 以終點時間重算剩餘秒數（不累積誤差）；到 0 恢復並朗讀
  useEffect(() => {
    if (!lock) return
    const id = window.setInterval(() => {
      const r = Math.ceil((lock.until - Date.now()) / 1000)
      if (r <= 0) {
        window.clearInterval(id)
        setLock(null)
        setRemaining(0)
        setReady(true)
      } else setRemaining(r)
    }, 250)
    return () => window.clearInterval(id)
  }, [lock])

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy || lock) return
    setBusy(true)
    setError(null)
    setEmptyEmail(false)
    setEmptyPassword(false)
    setReady(false)
    // 後端的 400 不指出是哪個欄位：空白或不像 Email（沒有 @）的欄位標為「請輸入 Email。」
    const badEmail = email.trim() === '' || !email.includes('@')
    try {
      await login(email, password)
      const me = await getMe()
      onLoggedIn(me)
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : new ApiError(0, null, null)
      if (apiErr.status === 429) {
        const sec = Math.max(1, apiErr.retryAfter ?? 60)
        setLock({ until: Date.now() + sec * 1000, first: sec })
        setRemaining(sec)
      } else if (apiErr.status === 400 && (badEmail || password === '')) {
        const noEmail = badEmail
        setEmptyEmail(noEmail)
        setEmptyPassword(password === '')
        const target = noEmail ? emailRef : passRef
        target.current?.focus()
      } else {
        setError(apiErr)
        if (apiErr.status === 401) {
          setPassword('')
          passRef.current?.focus()
        }
      }
      setBusy(false)
    }
  }

  const alertId = error || lock ? ERROR_ID : undefined
  const describe = (fieldErr: boolean, id: string) => [alertId, fieldErr ? id : undefined].filter(Boolean).join(' ') || undefined
  const locked = lock !== null
  return (
    <div className="login-wrap">
      <main className="login card" id="main-content">
        <h1>{PRODUCT_NAME}</h1>
        <p className="muted login-lead">請以個人帳號登入。</p>
        {expired && !error && !lock && (
          <div className="msg info" role="status">
            <p>登入已逾時，請重新登入。登入後會回到原本的頁面。</p>
          </div>
        )}
        {error && (
          <div className="msg error" role="alert" id={ERROR_ID}>
            {error.status === 401 ? (
              <p>Email 或密碼不正確。</p>
            ) : error.status === 400 ? (
              <p>請輸入 Email 與密碼。</p>
            ) : (
              <>
                <p>
                  <strong>目前無法登入</strong>
                </p>
                <p>
                  伺服器暫時無法回應，請稍後再試。
                  <RequestId error={error} />
                </p>
              </>
            )}
          </div>
        )}
        {lock && (
          <div className="msg warn" role="alert" id={ERROR_ID}>
            <p>
              <strong>登入失敗次數過多，這個 Email 暫時無法登入。</strong>
              {/* 畫面上的倒數每秒更新但不朗讀；讀屏只朗讀開始時的這一次 */}
              <span aria-hidden="true">請在 {countdownText(remaining)} 後再試。</span>
              <span className="sr">請在 {countdownText(lock.first)} 後再試。</span> 不論密碼是否正確，鎖定期間都無法登入。
            </p>
          </div>
        )}
        <form className="login-form" aria-label="登入" aria-busy={busy} noValidate onSubmit={submit}>
          <div className="field">
            <label htmlFor="login-email">Email</label>
            <input
              id="login-email"
              name="email"
              type="email"
              autoComplete="username"
              ref={emailRef}
              value={email}
              readOnly={busy}
              aria-invalid={emptyEmail ? true : undefined}
              aria-describedby={describe(emptyEmail, EMAIL_ERR_ID)}
              onChange={(e) => setEmail(e.target.value)}
            />
            {emptyEmail && (
              <span className="err-text" id={EMAIL_ERR_ID}>
                請輸入 Email。
              </span>
            )}
          </div>
          <div className="field">
            <label htmlFor="login-password">密碼</label>
            <input
              id="login-password"
              name="password"
              type="password"
              autoComplete="current-password"
              ref={passRef}
              value={password}
              readOnly={busy}
              aria-invalid={emptyPassword ? true : undefined}
              aria-describedby={describe(emptyPassword, PASS_ERR_ID)}
              onChange={(e) => setPassword(e.target.value)}
            />
            {emptyPassword && (
              <span className="err-text" id={PASS_ERR_ID}>
                請輸入密碼。
              </span>
            )}
          </div>
          <button className="btn" type="submit" aria-disabled={busy || locked}>
            {locked ? '請稍後再試' : busy ? '登入中…' : '登入'}
          </button>
        </form>
        <p role="status" className="sr">
          {busy ? '登入中…' : ready ? '現在可以再試一次' : ''}
        </p>
      </main>
    </div>
  )
}
