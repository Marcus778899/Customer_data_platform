// 驗證結果面板：錯誤與提醒各一份清單；每項可定位到節點（或前往旅程設定），field／規則號收進「進階資訊」。
import { useId } from 'react'
import type { Issue, ValidationReport } from '../api/types'

function Items({ items, onLocate, onSettings }: { items: Issue[]; onLocate: (id: string) => void; onSettings: () => void }) {
  return (
    <ul className="issues">
      {items.map((it, i) => (
        <li key={i}>
          <p>{it.message}</p>
          <details className="adv">
            <summary>進階資訊</summary>
            <p className="mono">
              欄位：{it.field}
              {it.rule !== null && `・規則 ${it.rule}`}
            </p>
          </details>
          {it.node_id ? (
            <button type="button" className="btn secondary sm" onClick={() => onLocate(it.node_id!)}>
              定位節點
            </button>
          ) : (
            <button type="button" className="btn secondary sm" onClick={onSettings}>
              前往旅程設定
            </button>
          )}
        </li>
      ))}
    </ul>
  )
}

export function ValidationPanel({
  report,
  busy,
  onRevalidate,
  onClose,
  onLocate,
  onSettings,
}: {
  report: ValidationReport
  busy: boolean
  onRevalidate: () => void
  onClose: () => void
  onLocate: (id: string) => void
  onSettings: () => void
}) {
  const hid = useId()
  const e = report.errors.length
  const w = report.warnings.length
  return (
    <aside className="side-panel" aria-labelledby={hid}>
      <div className="ph">
        <h2 id={hid}>驗證結果</h2>
        <button type="button" className="btn secondary sm" onClick={onClose}>
          關閉
        </button>
      </div>
      <p className={e ? 'warn-text' : 'muted'}>
        {e ? `${e} 個錯誤要修正才能送審` : '沒有錯誤，可以送審'}
        {w > 0 && `，另 ${w} 個提醒`}。
      </p>
      {e > 0 && (
        <>
          <h3 className="sub">錯誤</h3>
          <Items items={report.errors} onLocate={onLocate} onSettings={onSettings} />
        </>
      )}
      {w > 0 && (
        <>
          <h3 className="sub">提醒</h3>
          <Items items={report.warnings} onLocate={onLocate} onSettings={onSettings} />
        </>
      )}
      <p>
        <button type="button" className="btn secondary sm" aria-disabled={busy} onClick={() => !busy && onRevalidate()}>
          重新驗證
        </button>
      </p>
    </aside>
  )
}
