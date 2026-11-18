// 事件篩選與條件節點共用的比較下拉與「值當成：文字｜數字」切換（F-33；規格 docs/ui/journey-builder.md「事件篩選」）。
import { groupedOps, OP_GROUPS, opsFor } from './eventFilter'

const SYMBOL: Record<string, string> = { GT: '>', GTE: '≥', LT: '<', LTE: '≤' }

/**
 * 比較下拉的選項。型別不明的欄位分三組（optgroup），「比大小」四項右側附提示（例：amount ≥ 500）；
 * 已知型別的欄位是平的清單。labelOf 由呼叫端提供（條件節點與事件篩選的白話略有不同）。
 */
export function OperatorOptions({ field, current, labelOf, name }: { field: string; current: string; labelOf: (op: string) => string; name: string }) {
  if (!groupedOps(field)) {
    return (
      <>
        {opsFor(field, current).map((o) => (
          <option key={o.value} value={o.value}>
            {labelOf(o.value)}
          </option>
        ))}
      </>
    )
  }
  return (
    <>
      {OP_GROUPS.map((g, i) => (
        <optgroup key={g.label} label={g.label}>
          {g.ops.map((op) => (
            <option key={op} value={op}>
              {i === 0 ? `${labelOf(op)}\u3000${name || '欄位'} ${SYMBOL[op]} 500` : labelOf(op)}
            </option>
          ))}
        </optgroup>
      ))}
    </>
  )
}

/** 值當成：文字｜數字（預設文字）；只在型別不明欄位的 EQ／NEQ／IN／NOT_IN 顯示 */
export function KindToggle({ num, onChange, disabled }: { num: boolean; onChange: (num: boolean) => void; disabled?: boolean }) {
  return (
    <div className="kind-toggle seg" role="group" aria-label="值當成">
      <span className="kt-label">值當成</span>
      {([false, true] as const).map((v) => (
        <button key={String(v)} type="button" className="btn secondary sm" aria-pressed={num === v} disabled={disabled} onClick={() => onChange(v)}>
          {v ? '數字' : '文字'}
        </button>
      ))}
    </div>
  )
}

/** 面板標頭右側：「› 收合」＋「× 關閉」（收合鈕在 ≤1100px 由 CSS 隱藏，面板排到圖下方時不需要） */
export function PanelActions({ onCollapse, onClose }: { onCollapse?: () => void; onClose: () => void }) {
  return (
    <div className="ph-act">
      {onCollapse && (
        <button type="button" className="btn secondary sm panel-collapse" onClick={onCollapse}>
          <span aria-hidden="true">›</span> 收合
        </button>
      )}
      <button type="button" className="btn secondary sm" onClick={onClose}>
        <span aria-hidden="true">×</span> 關閉
      </button>
    </div>
  )
}
