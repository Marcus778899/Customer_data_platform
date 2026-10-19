import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, changePassword } from '../api/client'
import { useAuth } from '../auth'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { ErrorBox } from '../components/ui'

const HINT = '12 到 72 字元，不要求大小寫或符號；中文字每字約佔 3 位元組。'
const RULE = '新密碼長度須為 12 到 72 字元（UTF-8 位元組數不超過 72）。'

type FieldErrors = { current?: string; next?: string; again?: string }

export function PasswordPage() {
  const { user } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [again, setAgain] = useState('')
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [failure, setFailure] = useState<ApiError | null>(null)
  const h1 = useRef<HTMLHeadingElement>(null)
  const currentRef = useRef<HTMLInputElement>(null)
  const nextRef = useRef<HTMLInputElement>(null)
  const againRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    h1.current?.focus()
  }, [])

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    setDone(false)
    setFailure(null)
    if (next !== again) {
      setErrors({ again: '兩次輸入的新密碼不一致。' })
      againRef.current?.focus()
      return
    }
    setErrors({})
    setBusy(true)
    try {
      await changePassword(current, next)
      setCurrent('')
      setNext('')
      setAgain('')
      setDone(true)
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : new ApiError(0, null, null)
      if (apiErr.status === 400) {
        setErrors({ next: RULE })
        nextRef.current?.focus()
      } else if (apiErr.status === 401 && apiErr.code === 'INVALID_CREDENTIALS') {
        setErrors({ current: '目前密碼不正確。' })
        currentRef.current?.focus()
      } else if (apiErr.status !== 401) {
        // 401 UNAUTHENTICATED 已由 App 導回登入
        setFailure(apiErr)
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Crumbs items={[{ label: '旅程', to: '/' }, { label: '修改密碼' }]} />
      <div className="pagehead">
        <div>
          <h1 tabIndex={-1} ref={h1}>
            修改密碼
          </h1>
          <p className="muted">
            登入帳號：{user.display_name}（{user.email}）
          </p>
        </div>
      </div>
      <div role="status" className="pw-status">
        {done && (
          <div className="msg success">
            <p>
              <strong>密碼已更新。</strong>其他已登入的裝置不會被登出。
            </p>
          </div>
        )}
      </div>
      {failure &&
        (failure.status === 403 && failure.code === 'CSRF_INVALID' ? (
          <div className="msg error pw-alert" role="alert">
            <p>
              <strong>操作未完成，請重新整理頁面</strong>
            </p>
          </div>
        ) : (
          <div className="pw-alert">
            <ErrorBox title="密碼沒有更新" error={failure} />
          </div>
        ))}
      <form className="card pw-form" aria-label="修改密碼" aria-busy={busy} noValidate onSubmit={submit}>
        <div className="field">
          <label htmlFor="pw-current">目前密碼</label>
          <input
            id="pw-current"
            type="password"
            autoComplete="current-password"
            ref={currentRef}
            value={current}
            readOnly={busy}
            aria-invalid={errors.current ? true : undefined}
            aria-describedby={errors.current ? 'pw-current-error' : undefined}
            onChange={(e) => setCurrent(e.target.value)}
          />
          {errors.current && (
            <span className="err-text" id="pw-current-error">
              {errors.current}
            </span>
          )}
        </div>
        <div className="field">
          <label htmlFor="pw-new">新密碼</label>
          <input
            id="pw-new"
            type="password"
            autoComplete="new-password"
            ref={nextRef}
            value={next}
            readOnly={busy}
            aria-invalid={errors.next ? true : undefined}
            aria-describedby={errors.next ? 'pw-new-hint pw-new-error' : 'pw-new-hint'}
            onChange={(e) => setNext(e.target.value)}
          />
          <span className="hint" id="pw-new-hint">
            {HINT}
          </span>
          {errors.next && (
            <span className="err-text" id="pw-new-error">
              {errors.next}
            </span>
          )}
        </div>
        <div className="field">
          <label htmlFor="pw-again">再輸入一次新密碼</label>
          <input
            id="pw-again"
            type="password"
            autoComplete="new-password"
            ref={againRef}
            value={again}
            readOnly={busy}
            aria-invalid={errors.again ? true : undefined}
            aria-describedby={errors.again ? 'pw-again-error' : undefined}
            onChange={(e) => setAgain(e.target.value)}
          />
          {errors.again && (
            <span className="err-text" id="pw-again-error">
              {errors.again}
            </span>
          )}
        </div>
        <div className="row">
          <button className="btn" type="submit" aria-disabled={busy}>
            {busy ? '更新中…' : '更新密碼'}
          </button>
          <AppLink className="btn secondary" to="/">
            取消
          </AppLink>
        </div>
      </form>
    </>
  )
}
