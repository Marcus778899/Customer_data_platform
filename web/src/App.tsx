import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, getMe, logout, setUnauthorizedHandler } from './api/client'
import { AuthContext } from './auth'
import { ErrorBox } from './components/ui'
import { LoginPage } from './pages/LoginPage'
import { DetailPage } from './pages/DetailPage'
import { SearchPage } from './pages/SearchPage'
import { navigate, safeNext, useLocation } from './router'

type AuthState =
  | { status: 'checking' }
  | { status: 'in'; username: string }
  | { status: 'out'; expired: boolean }
  | { status: 'error'; error: ApiError }

export function App() {
  const loc = useLocation()
  const [auth, setAuth] = useState<AuthState>({ status: 'checking' })
  const [attempt, setAttempt] = useState(0)

  // 任何 API 回 401 → 回登入（契約 §1.2）
  useEffect(() => {
    setUnauthorizedHandler(() => setAuth({ status: 'out', expired: true }))
    return () => setUnauthorizedHandler(null)
  }, [])

  // 是否已登入：GET /auth/me（cookie 為 HttpOnly，前端讀不到）
  useEffect(() => {
    let cancelled = false
    getMe().then(
      (me) => !cancelled && setAuth({ status: 'in', username: me.username }),
      (e: unknown) => {
        if (cancelled) return
        if (e instanceof ApiError && e.status === 401) setAuth({ status: 'out', expired: false })
        else setAuth({ status: 'error', error: e instanceof ApiError ? e : new ApiError(0, null, null) })
      },
    )
    return () => {
      cancelled = true
    }
  }, [attempt])

  const { pathname, search } = loc
  useEffect(() => {
    if (auth.status === 'out' && pathname !== '/login') {
      navigate(`/login?next=${encodeURIComponent(pathname + search)}`, { replace: true })
    } else if (auth.status === 'in' && pathname === '/login') {
      navigate(safeNext(search), { replace: true })
    }
  }, [auth.status, pathname, search])

  const doLogout = useCallback(async () => {
    try {
      await logout()
    } catch {
      // 登出失敗也回登入頁；session 仍有效時下次進站會再被導回
    }
    setAuth({ status: 'out', expired: false })
    navigate('/login', { replace: true })
  }, [])

  const authInfo = useMemo(
    () => (auth.status === 'in' ? { username: auth.username, logout: doLogout } : null),
    [auth, doLogout],
  )

  if (auth.status === 'checking') {
    return (
      <main className="app" role="status" aria-busy="true">
        <p className="muted">載入中…</p>
      </main>
    )
  }
  if (auth.status === 'error') {
    return (
      <main className="app">
        <ErrorBox
          title="無法確認登入狀態"
          error={auth.error}
          onRetry={() => {
            setAuth({ status: 'checking' })
            setAttempt((n) => n + 1)
          }}
        />
      </main>
    )
  }
  if (auth.status === 'out') {
    if (pathname !== '/login') return null
    return (
      <LoginPage
        expired={auth.expired}
        onLoggedIn={(username) => setAuth({ status: 'in', username })}
      />
    )
  }
  if (!authInfo || pathname === '/login') return null

  const detail = /^\/enrollments\/([^/]+)\/?$/.exec(pathname)
  return (
    <AuthContext.Provider value={authInfo}>
      {pathname === '/' ? (
        <SearchPage loc={loc} />
      ) : detail ? (
        <DetailPage id={decodeURIComponent(detail[1])} />
      ) : (
        <div className="app">
          <p className="msg card">
            <strong>找不到這個頁面</strong>
            <br />
            <a
              href="/"
              onClick={(e) => {
                e.preventDefault()
                navigate('/')
              }}
            >
              回到查詢
            </a>
          </p>
        </div>
      )}
    </AuthContext.Provider>
  )
}
