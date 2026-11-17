import { useMemo, useState } from 'react'
import type { JourneyVersion, NodeStat } from '../api/types'
import { FlowGraph } from './FlowGraph'
import { buildModel, type TraceOverlay } from './model'
import { NodePanel } from './NodePanel'

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
  stats,
  panel = false,
}: {
  def: JourneyVersion
  overlay: TraceOverlay | null
  label: string
  /** 旅程頁的節點統計；undefined = 沒有（載入中、失敗或軌跡頁） */
  stats?: readonly NodeStat[]
  /** 選取節點時在旁邊開明細面板（旅程頁） */
  panel?: boolean
}) {
  const model = useMemo(() => buildModel(def, overlay, stats), [def, overlay, stats])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selected = panel && selectedId ? (def.nodes.find((n) => n.id === selectedId) ?? null) : null
  // 疊上軌跡時，文字版另附各狀態的節點數（讀屏不用看圖也知道進度）
  const count = (s: string) => model.nodes.filter((n) => n.data.state === s).length
  const stateNote = overlay
    ? [['visited', '已完成'], ['current', '目前在這裡'], ['unvisited', '接下來']]
        .filter(([k]) => count(k) > 0)
        .map(([k, t]) => `${t} ${count(k)} 個`)
        .join('、')
    : ''
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
      <div className={selected ? 'graph-split' : undefined}>
        <FlowGraph model={model} label={label} selectedId={selectedId} onSelect={setSelectedId} />
        {selected && (
          <NodePanel def={def} node={selected} stat={stats?.find((x) => x.node_id === selected.id)} onClose={() => setSelectedId(null)} />
        )}
      </div>
      <details>
        <summary>節點清單（文字版，供讀屏與鍵盤瀏覽）</summary>
        <ol>
          <li>
            {model.textOrder.join(' → ')}
            {stateNote && `（狀態：${stateNote}）`}
          </li>
        </ol>
      </details>
      <p className="graph-note muted">窄螢幕：圖可左右捲動，建議改看上方「節點清單」。</p>
    </>
  )
}
