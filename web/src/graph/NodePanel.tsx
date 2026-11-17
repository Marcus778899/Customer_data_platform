import type { JourneyNode, JourneyVersion, NodeStat } from '../api/types'
import { fmtCount } from './FlowGraph'
import { categoryOf, loopNodeIds, outsOf } from './model'

const PORT_TEXT: Record<string, string> = {
  yes: '是',
  no: '否',
  occurred: '事件發生',
  timeout: '逾時',
  next: '繼續',
  sent: '已發送',
}
export const portText = (port: string) => PORT_TEXT[port] ?? port

/** 選取節點的明細：人數、進入次數、目前停在這裡、各出口人次（統計沒回來時只有設定） */
export function NodePanel({
  def,
  node,
  stat,
  onClose,
}: {
  def: JourneyVersion
  node: JourneyNode
  stat: NodeStat | undefined
  onClose: () => void
}) {
  const name = node.name ?? node.type
  const nameOf = (id: string) => {
    const t = def.nodes.find((n) => n.id === id)
    return t ? (t.name ?? t.type) : id
  }
  const outs = Object.entries(outsOf(node))
  const inLoop = loopNodeIds(def).has(node.id)
  return (
    <aside className="side-panel node-panel" aria-label={name}>
      <div className="ph">
        <h2>{name}</h2>
        <button type="button" className="btn secondary sm" aria-label="關閉面板" onClick={onClose}>
          關閉
        </button>
      </div>
      <p className="muted">
        {categoryOf(node.type)[1]}節點・<span className="mono">{node.type}</span>
      </p>
      {inLoop && <p className="muted">這個節點在迴圈裡，同一位客戶可能經過多次。</p>}
      {stat && (
        <dl className="kv">
          <dt>人數（曾進入的客戶）</dt>
          <dd>{fmtCount(stat.entered)}</dd>
          <dt>進入次數（含迴圈）</dt>
          <dd>{fmtCount(stat.visits)}</dd>
          <dt>目前停在這裡</dt>
          <dd>{fmtCount(stat.current)}</dd>
        </dl>
      )}
      {outs.length > 0 && (
        <>
          <h3 className="sec">{stat ? '離開時走的出口（人次）' : '出口'}</h3>
          <ul className="plain-list">
            {outs.map(([port, target]) => (
              <li key={port}>
                <span>{portText(port)}</span> → <span>{nameOf(target)}</span>
                {stat && <> <b>{fmtCount(stat.out_ports?.[port] ?? 0)}</b></>}
              </li>
            ))}
          </ul>
        </>
      )}
    </aside>
  )
}
