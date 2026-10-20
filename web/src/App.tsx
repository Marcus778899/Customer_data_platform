import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, getMe, logout, setForbiddenHandler, setUnauthorizedHandler } from './api/client'
import type { CurrentUser } from './api/types'
import { AuthContext } from './auth'
import { PRODUCT_NAME, Shell, type NavKey } from './components/Shell'
import { ErrorBox } from './components/ui'
import { AudiencePage } from './pages/AudiencePage'
import { DetailPage } from './pages/DetailPage'
import { ForbiddenPage } from './pages/ForbiddenPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { PasswordPage } from './pages/PasswordPage'
import { SearchPage } from './pages/SearchPage'
import { navigate, safeNext, useLocation } from './router'
import { consumeLoggedIn, markLoggedIn } from './sessionFlag'

type AuthState =
  | { status: 'checking' }
  | { status: 'in'; user: CurrentUser }
  | { status: 'out'; expired: boolean }
  | { status: 'error'; error: ApiError }

function currentNav(pathname: string): NavKey {
  if (pathname === '/' || pathname.startsWith('/enrollments/')) return 'journeys'
  if (pathname === '/customers' || pathname.startsWith('/customers/')) return 'customers'
  if (pathname === '/audience') return 'audience'
  return null
}

export function App() {
  const loc = useLocation()
  const [auth, setAuth] = useState<AuthState>({ status: 'checking' })
  const [attempt, setAttempt] = useState(0)
  // 資料請求 403 FORBIDDEN 時記下當下位置；離開該位置（任何導覽）即清除
  const [forbiddenAt, setForbiddenAt] = useState<string | null>(null)

  useEffect(() => {
    document.title = PRODUCT_NAME
  }, [])

  // 任何 API 回 401 UNAUTHENTICATED → 回登入（auth.md §3）
  useEffect(() => {
    setUnauthorizedHandler(() => {
      consumeLoggedIn()
      setAuth({ status: 'out', expired: true })
    })
    setForbiddenHandler(() => setForbiddenAt(window.location.pathname + window.location.search))
    const clear = () => setForbiddenAt(null)
    window.addEventListener('cdp:navigate', clear)
    window.addEventListener('popstate', clear)
    return () => {
      setUnauthorizedHandler(null)
      setForbiddenHandler(null)
      window.removeEventListener('cdp:navigate', clear)
      window.removeEventListener('popstate', clear)
    }
  }, [])

  // 是否已登入：GET /auth/me（cookie 為 HttpOnly，前端讀不到）
  useEffect(() => {
    let cancelled = false
    getMe().then(
      (me) => {
        if (cancelled) return
        markLoggedIn()
        setAuth({ status: 'in', user: me })
      },
      (e: unknown) => {
        if (cancelled) return
        if (e instanceof ApiError && e.status === 401) setAuth({ status: 'out', expired: consumeLoggedIn() })
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
    consumeLoggedIn()
    setAuth({ status: 'out', expired: false })
    navigate('/login', { replace: true })
  }, [])

  const authInfo = useMemo(() => (auth.status === 'in' ? { user: auth.user, logout: doLogout } : null), [auth, doLogout])

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
        onLoggedIn={(me) => {
          markLoggedIn()
          setAuth({ status: 'in', user: me })
        }}
      />
    )
  }
  if (!authInfo || pathname === '/login') return null

  const detail = /^\/enrollments\/([^/]+)\/?$/.exec(pathname)
  const here = pathname + search
  let page
  if (forbiddenAt === here) page = <ForbiddenPage />
  else if (pathname === '/') page = <SearchPage loc={loc} />
  else if (detail) page = <DetailPage id={decodeURIComponent(detail[1])} />
  else if (pathname === '/audience') page = <AudiencePage />
  else if (pathname === '/account/password') page = <PasswordPage />
  else page = <NotFoundPage />

  return (
    <AuthContext.Provider value={authInfo}>
      <Shell current={currentNav(pathname)} menuKey={here}>
        {page}
      </Shell>
    </AuthContext.Provider>
  )
}
