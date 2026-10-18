import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, getMe, login } from '../api/client'
import { RequestId } from '../components/ui'

interface Props {
  /** 因 session 逾時（API 回 401）被導回時為 true */
  expired: boolean
  onLoggedIn: (username: string) => void
}

const ERROR_ID = 'login-error'

export function LoginPage({ expired, onLoggedIn }: Props) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const userRef = useRef<HTMLInputElement>(null)
  const passRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    userRef.current?.focus()
  }, [])

  // 失敗後焦點回到密碼欄並清空（UI-01）
  useEffect(() => {
    if (error) passRef.current?.focus()
  }, [error])

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await login(username, password)
      const me = await getMe()
      onLoggedIn(me.username)
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, null, null))
      setPassword('')
      setBusy(false)
    }
  }

  const describedBy = error ? ERROR_ID : undefined
  return (
    <div className="app">
      <main className="login card">
        <h1>客戶旅程軌跡</h1>
        {expired && !error && (
          <div className="msg info" role="status">
            <p>
              <strong>登入已逾時，請重新登入</strong>
            </p>
            <p>登入後會回到剛才的頁面。</p>
          </div>
        )}
        {error && (
          <div className="msg error" role="alert" id={ERROR_ID}>
            {error.status === 401 ? (
              <p>
                <strong>帳號或密碼不正確</strong>
              </p>
            ) : error.status === 400 ? (
              <p>
                <strong>請輸入帳號與密碼</strong>
              </p>
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
        <form className="login-form" aria-label="登入" aria-busy={busy} onSubmit={submit}>
          <div className="field">
            <label htmlFor="login-username">帳號</label>
            <input
              id="login-username"
              name="username"
              autoComplete="username"
              ref={userRef}
              value={username}
              readOnly={busy}
              aria-describedby={describedBy}
              onChange={(e) => setUsername(e.target.value)}
            />
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
              aria-describedby={describedBy}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          <button className="btn" type="submit" aria-disabled={busy}>
            {busy ? '登入中…' : '登入'}
          </button>
        </form>
        {busy && (
          <p role="status" className="muted login-status">
            登入中…
          </p>
        )}
      </main>
    </div>
  )
}
