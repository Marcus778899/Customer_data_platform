// 節點庫（取代頂部工具列）：四組九個項目，按一下／Enter 新增，桌機也可拖進畫布。
// ≥1101px 側欄、≤1100px 圖示軌、≤640px 上方橫排（只用 CSS 切換，同一份元素）。
import { useEffect, useRef, useState } from 'react'
import { Icon } from '../components/CanvasIcons'
import { categoryOf, NODE_MIME, PALETTE, typeLabel } from './model'

export function NodeLibrary({ onAdd, locked }: { onAdd: (type: string) => void; locked: boolean }) {
  const box = useRef<HTMLElement>(null)
  // 橫排捲到最右邊時拿掉右緣漸層
  const [end, setEnd] = useState(false)
  useEffect(() => {
    const el = box.current
    if (!el) return
    const read = () => setEnd(el.scrollLeft + el.clientWidth >= el.scrollWidth - 1)
    read()
    el.addEventListener('scroll', read, { passive: true })
    return () => el.removeEventListener('scroll', read)
  }, [])
  return (
    <aside className={`nodelib${end ? ' end' : ''}`} aria-label="節點庫" ref={box}>
      <h2 className="nl-title">節點庫</h2>
      <p className="nl-tip">拖到畫布，或按一下新增。已選取節點時，新節點會自動接在它後面。</p>
      {PALETTE.map((g) => (
        <div className="nl-group" role="group" aria-label={g.group} key={g.group}>
          <h3 className="nl-gh">{g.group}</h3>
          {g.items.map((it) => (
            <button
              key={it.type}
              type="button"
              className={`nl-item cat-${categoryOf(it.type).category}`}
              title={`${typeLabel(it.type)}：${it.hint}`}
              aria-disabled={locked ? true : undefined}
              draggable={!locked}
              onDragStart={(e) => {
                if (locked) return e.preventDefault()
                e.dataTransfer.setData(NODE_MIME, it.type)
                e.dataTransfer.effectAllowed = 'copy'
              }}
              onClick={() => !locked && onAdd(it.type)}
            >
              <Icon name={it.type} />
              <span className="nl-tx">
                <b>{typeLabel(it.type)}</b>
                <small>{it.hint}</small>
              </span>
              <span className="nl-grip" aria-hidden="true">
                <Icon name="grip" size={16} />
              </span>
            </button>
          ))}
        </div>
      ))}
    </aside>
  )
}
