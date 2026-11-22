// 事件篩選與條件節點共用的比較下拉與「值當成：文字｜數字」切換（F-33；規格 docs/ui/journey-builder.md「事件篩選」）。
import { OP_GROUPS, opMenuLabel, opsFor } from './eventFilter'

/**
 * 比較下拉的選項：條件節點與事件篩選同一套，分「數字比大小」「比對內容」「有沒有填」三組（optgroup），
 * 選項只寫比較方式、不帶欄位名；型別已知的欄位不列不適用的項目（整組沒項目就不列該組）。
 */
export function OperatorOptions({ field, current }: { field: string; current: string }) {
  const allowed = new Set(opsFor(field, current).map((o) => o.value))
  const grouped = new Set(OP_GROUPS.flatMap((g) => g.ops))
  const strays = [...allowed].filter((v) => !grouped.has(v))
  return (
    <>
      {OP_GROUPS.map((g) => {
        const ops = g.ops.filter((o) => allowed.has(o))
        return ops.length ? (
          <optgroup key={g.label} label={g.label}>
            {ops.map((op) => (
              <option key={op} value={op}>
                {opMenuLabel(op)}
              </option>
            ))}
          </optgroup>
        ) : null
      })}
      {strays.map((op) => (
        <option key={op} value={op}>
          {opMenuLabel(op)}
        </option>
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
