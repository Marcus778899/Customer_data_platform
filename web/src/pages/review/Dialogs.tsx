// 審核與停止的對話框（rv-dialogs）：送審、核准、退回、撤回、停止、捨棄草稿
import { useState, type ReactNode } from 'react'
import { ApiError } from '../../api/client'
import { RequestId } from '../../components/ui'
import { friendlyTime } from '../../canvas/Banners'
import { describeError } from './model'
import { Dialog, TextField } from './Dialog'

interface Common {
  onClose: () => void
  /** 動作成功後（對話框由呼叫端關閉） */
  onDone: () => void
  /** 「重新載入」：呼叫端重新取資料並關閉對話框 */
  onReload: () => void
}

/** 執行動作：忙碌中不重複送、失敗留在對話框顯示 */
function useRun(onDone: () => void) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  async function run(fn: () => Promise<unknown>) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await fn()
      onDone()
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return
      setError(e)
      setBusy(false)
    }
  }
  return { busy, error, run }
}

function Failure({ error, onReload }: { error: unknown; onReload: () => void }) {
  if (error === null) return null
  const d = describeError(error)
  return (
    <div className="msg error dlg-msg" role="alert">
      <p>
        {d.text}
        {error instanceof ApiError && !d.reload && <RequestId error={error} />}
      </p>
      {d.reload && (
        <button type="button" className="btn secondary sm" onClick={onReload}>
          重新載入
        </button>
      )}
    </div>
  )
}

function Actions({ children }: { children: ReactNode }) {
  return <div className="dlg-actions">{children}</div>
}

function Confirm({
  label,
  busy,
  disabled = false,
  danger = false,
  onClick,
}: {
  label: string
  busy: boolean
  disabled?: boolean
  danger?: boolean
  onClick: () => void
}) {
  const off = busy || disabled
  return (
    <button
      type="button"
      className={`btn${danger ? ' danger-solid' : ''}`}
      aria-disabled={off}
      onClick={() => !off && onClick()}
    >
      {busy ? '處理中…' : label}
    </button>
  )
}

const Cancel = ({ onClose }: { onClose: () => void }) => (
  <button type="button" className="btn secondary" onClick={onClose}>
    取消
  </button>
)

// ---------- 送審（編輯畫布） ----------
export function SubmitDialog({
  name,
  publishedVersion,
  draftVersion,
  onConfirm,
  onClose,
}: {
  name: string
  publishedVersion: number | null
  draftVersion: number
  onConfirm: (note: string) => Promise<unknown>
  onClose: () => void
}) {
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  async function go() {
    setBusy(true)
    try {
      await onConfirm(note.trim())
    } finally {
      onClose()
    }
  }
  return (
    <Dialog title={`把「${name}」送審？`} onClose={onClose}>
      <ul className="dlg-list">
        <li>會以草稿 v{draftVersion} 的目前內容送審</li>
        <li>
          送審後草稿鎖定；管理員核准前，
          {publishedVersion !== null ? `上線的仍是 v${publishedVersion}` : '還沒有上線的版本'}
        </li>
      </ul>
      <TextField label="給審核者的說明（選填）" value={note} onChange={setNote} autoFocus />
      <Actions>
        <Cancel onClose={onClose} />
        <Confirm label="送審" busy={busy} onClick={() => void go()} />
      </Actions>
    </Dialog>
  )
}

// ---------- 核准 ----------
export function ApproveDialog({
  version,
  publishedVersion,
  run,
  ...c
}: Common & { version: number; publishedVersion: number | null; run: (note: string) => Promise<unknown> }) {
  const [note, setNote] = useState('')
  const r = useRun(c.onDone)
  return (
    <Dialog title={`核准並發佈 v${version}？`} onClose={c.onClose}>
      <ul className="dlg-list">
        <li>新進入的客戶從 v{version} 開始走</li>
        {publishedVersion !== null && <li>已在 v{publishedVersion} 等待中的客戶，照 v{publishedVersion} 走完，不受影響</li>}
        <li>發佈後 v{version} 不能修改，要改只能再發新版</li>
      </ul>
      <TextField label="備註（選填）" value={note} onChange={setNote} autoFocus />
      <Failure error={r.error} onReload={c.onReload} />
      <Actions>
        <Cancel onClose={c.onClose} />
        <Confirm label="核准並發佈" busy={r.busy} onClick={() => void r.run(() => run(note.trim()))} />
      </Actions>
    </Dialog>
  )
}

// ---------- 退回 ----------
export function RejectDialog({
  submitter,
  submittedAt,
  run,
  ...c
}: Common & { submitter: string; submittedAt: string | null; run: (reason: string) => Promise<unknown> }) {
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const r = useRun(c.onDone)
  function go() {
    if (!reason.trim()) {
      setMissing(true)
      return
    }
    void r.run(() => run(reason.trim()))
  }
  return (
    <Dialog title="退回這次送審" onClose={c.onClose}>
      <p>
        {submitter}
        {submittedAt ? ` ${friendlyTime(submittedAt)} 送審` : ' 送審'}。草稿會解鎖，並把原因顯示給送審者。
      </p>
      <TextField
        label="退回原因（必填，1 到 200 字）"
        value={reason}
        onChange={(v) => {
          setReason(v)
          setMissing(false)
        }}
        autoFocus
        error={missing ? '請填寫退回原因。' : null}
      />
      <Failure error={r.error} onReload={c.onReload} />
      <Actions>
        <Cancel onClose={c.onClose} />
        <Confirm label="退回" busy={r.busy} onClick={go} />
      </Actions>
    </Dialog>
  )
}

// ---------- 撤回 ----------
export function WithdrawDialog({ run, ...c }: Common & { run: (reason: string) => Promise<unknown> }) {
  const [reason, setReason] = useState('')
  const r = useRun(c.onDone)
  return (
    <Dialog title="撤回送審？" onClose={c.onClose}>
      <p>撤回後草稿解鎖，可以繼續修改，之後再送審。</p>
      <TextField label="原因（選填）" value={reason} onChange={setReason} autoFocus />
      <Failure error={r.error} onReload={c.onReload} />
      <Actions>
        <Cancel onClose={c.onClose} />
        <Confirm label="撤回送審" busy={r.busy} onClick={() => void r.run(() => run(reason.trim()))} />
      </Actions>
    </Dialog>
  )
}

// ---------- 停止 ----------
export function StopDialog({
  journeyName,
  hasPending,
  run,
  ...c
}: Common & { journeyName: string; hasPending: boolean; run: (reason: string) => Promise<unknown> }) {
  const [reason, setReason] = useState('')
  const [typed, setTyped] = useState('')
  const r = useRun(c.onDone)
  const ready = reason.trim() !== '' && typed === journeyName
  return (
    <Dialog title={`停止「${journeyName}」？`} onClose={c.onClose} danger>
      <div className="msg error dlg-msg" role="alert">
        <p>
          <strong>進行中的客戶會被中止，停止後不能恢復。</strong>
        </p>
      </div>
      <ul className="dlg-list">
        <li>立即不再接受新客戶</li>
        <li>還沒送出的訊息全部取消，不會再發出</li>
        <li>進行中的客戶會陸續轉為「已中止」，可能需要幾分鐘</li>
        {hasPending && <li>審核中的草稿會一併撤回</li>}
      </ul>
      <TextField label="停止原因（必填，1 到 200 字）" value={reason} onChange={setReason} autoFocus />
      <div className="field dlg-field">
        <label htmlFor="stop-confirm">輸入旅程名稱「{journeyName}」以確認</label>
        <input id="stop-confirm" type="text" value={typed} autoComplete="off" onChange={(e) => setTyped(e.target.value)} />
      </div>
      <Failure error={r.error} onReload={c.onReload} />
      <Actions>
        <Cancel onClose={c.onClose} />
        <Confirm label="停止旅程" danger busy={r.busy} disabled={!ready} onClick={() => void r.run(() => run(reason.trim()))} />
      </Actions>
    </Dialog>
  )
}

// ---------- 捨棄草稿 ----------
export function DiscardDialog({ run, ...c }: Common & { run: () => Promise<unknown> }) {
  const r = useRun(c.onDone)
  return (
    <Dialog title="捨棄草稿？" onClose={c.onClose} danger>
      <p>草稿的修改會全部刪除，不能恢復；目前上線的版本不受影響。</p>
      <Failure error={r.error} onReload={c.onReload} />
      <Actions>
        <Cancel onClose={c.onClose} />
        <Confirm label="捨棄草稿" danger busy={r.busy} onClick={() => void r.run(run)} />
      </Actions>
    </Dialog>
  )
}
