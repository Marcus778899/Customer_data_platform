import { useMemo } from 'react'
import type { JourneyVersion } from '../api/types'
import { FlowGraph } from './FlowGraph'
import { buildModel, type TraceOverlay } from './model'

const CATEGORIES = [
  ['source', '來源'],
  ['wait', '等待'],
  ['branch', '分流'],
  ['send', '發送'],
  ['end', '出口'],
] as const

/** 圖例＋圖＋文字版節點清單＋窄螢幕提示；overlay 有值 = 疊上軌跡（三態） */
export function GraphSection({
  def,
  overlay,
  label,
}: {
  def: JourneyVersion
  overlay: TraceOverlay | null
  label: string
}) {
  const model = useMemo(() => buildModel(def, overlay), [def, overlay])
  return (
    <>
      <ul className="legend" aria-label="圖例">
        {CATEGORIES.map(([k, text]) => (
          <li key={k}>
            <span className="sw" style={{ borderLeft: `6px solid var(--color-node-${k})` }} />
            {text}
          </li>
        ))}
        {overlay && (
          <>
            <li>
              <span className="sw v" />
              已經過（✓）
            </li>
            <li>
              <span className="sw c" />
              目前位置（●）
            </li>
            <li>
              <span className="sw u" />
              未經過（虛線）
            </li>
            <li>藍粗線：實際走的路徑</li>
          </>
        )}
        {model.edges.some((e) => e.back) && <li>點狀線＝回到前面節點的迴圈</li>}
      </ul>
      <FlowGraph model={model} label={label} />
      <details>
        <summary>節點清單（文字版，供讀屏與鍵盤瀏覽）</summary>
        <ol>
          <li>{model.textOrder.join(' → ')}</li>
        </ol>
      </details>
      <p className="graph-note muted">窄螢幕：圖可左右捲動，建議改看上方「節點清單」。</p>
    </>
  )
}
