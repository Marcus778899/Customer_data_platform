// 刪除旅程確認（alertdialog，canvas-interaction.md §6、journey-delete.md）：預設焦點「取消」、不要求輸入名稱
import { useId, useState } from 'react'
import { ApiError, deleteJourney } from '../../api/client'
import { RequestId } from '../../components/ui'
import { showFlash } from '../../components/Flash'
import { navigate } from '../../router'
import { Dialog } from './Dialog'

/** 依錯誤碼（契約 §3.6）的白話文案；retry = 這種失敗可以直接再試一次 */
export function describeDeleteError(e: unknown): { text: string; retry: boolean; reload: boolean } {
  const err = e instanceof ApiError ? e : new ApiError(0, null, null)
  if (err.status === 409 && err.code === 'DRAFT_IN_REVIEW')
    return { text: '這個旅程剛被送審，目前不能刪除。請先撤回送審再試一次。', retry: false, reload: true }
  if (err.status === 409 && err.code === 'JOURNEY_PUBLISHED')
    return { text: '這個旅程已發佈過（已有版本），不能刪除。要讓它不再運作，請改用「停止旅程」。', retry: false, reload: true }
  if (err.status === 403) return { text: '你的角色沒有權限刪除旅程。', retry: false, reload: false }
  return { text: '刪除失敗，請稍後再試。', retry: true, reload: false }
}

export function DeleteDialog({ id, name, onClose, onReload }: { id: string; name: string; onClose: () => void; onReload: () => void }) {
  const descId = useId()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function go() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await deleteJourney(id)
      showFlash(`已刪除『${name}』`)
      navigate('/journeys')
    } catch (e) {
      if (e instanceof ApiError && e.status === 404 && e.code === 'JOURNEY_NOT_FOUND') {
        // 別人已刪掉：不卡在已不存在的旅程頁
        navigate('/journeys')
        return
      }
      if (e instanceof ApiError && e.status === 401) return
      setError(e)
      setBusy(false)
    }
  }

  const d = error === null ? null : describeDeleteError(error)
  return (
    <Dialog title={`刪除「${name}」？`} onClose={() => !busy && onClose()} danger alert describedBy={descId}>
      <div id={descId} className="dlg-body">
        <p>刪除後無法復原。</p>
        <ul className="dlg-list">
          <li>旅程和草稿內容會一起刪除</li>
          <li>這個旅程還沒發佈，沒有客戶在旅程中</li>
        </ul>
      </div>
      {d && (
        <div className="msg error dlg-msg" role="alert">
          <p>
            {d.text}
            {error instanceof ApiError && <RequestId error={error} />}
          </p>
        </div>
      )}
      <div className="dlg-actions">
        <button type="button" className="btn secondary" data-autofocus="" aria-disabled={busy} onClick={() => !busy && onClose()}>
          {d && !d.retry ? '關閉' : '取消'}
        </button>
        {d?.reload && (
          <button type="button" className="btn" onClick={onReload}>
            重新載入旅程
          </button>
        )}
        {(!d || d.retry) && (
          <button type="button" className="btn danger-solid" aria-disabled={busy} onClick={() => void go()}>
            {busy ? '刪除中…' : d ? '重試刪除' : '刪除旅程'}
          </button>
        )}
      </div>
    </Dialog>
  )
}
