// 新建草稿：輸入旅程名稱 → POST /journeys/drafts → 進入編輯畫布（journeys.md §3）
import { useId, useState, type FormEvent } from 'react'
import { ApiError, createDraft } from '../api/client'
import { RequestId } from '../components/ui'
import { Crumbs } from '../components/Shell'
import { navigate } from '../router'

export function NewDraftPage() {
  const id = useId()
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    const trimmed = name.trim()
    if (!trimmed) {
      setError(new ApiError(400, 'EMPTY_NAME', null))
      return
    }
    setBusy(true)
    setError(null)
    try {
      const r = await createDraft(trimmed)
      navigate(`/journeys/${encodeURIComponent(r.journey_id)}/edit`)
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, null, null))
      setBusy(false)
    }
  }

  return (
    <>
      <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '新增旅程' }]} />
      <h1>新增旅程</h1>
      <form className="card new-draft" onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor={id}>旅程名稱</label>
          <input id={id} type="text" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} aria-invalid={error?.code === 'EMPTY_NAME'} />
          <span className="hint">之後可以在「旅程設定」修改。建立後先是只有「開始」的草稿。</span>
        </div>
        {error && (
          <div className="msg error" role="alert">
            <p>
              {error.code === 'EMPTY_NAME' ? '請輸入旅程名稱。' : error.status === 400 ? '旅程名稱不符合規定，請修改後再試。' : '暫時無法建立草稿，請稍後再試。'}
              {error.code !== 'EMPTY_NAME' && <RequestId error={error} />}
            </p>
          </div>
        )}
        <p>
          <button className="btn" type="submit" aria-disabled={busy}>
            {busy ? '建立中…' : '建立草稿'}
          </button>
        </p>
      </form>
    </>
  )
}
