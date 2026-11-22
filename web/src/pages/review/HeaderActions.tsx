// 旅程頁標頭動作區（canvas-interaction.md §4）：主要按鈕＋「⋯ 更多動作」選單；停用原因整個標頭只有一行（reasonId）
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Icon } from '../../components/CanvasIcons'
import type { ActionKey, ActionSpec } from './model'

const CLASS = { primary: 'btn', secondary: 'btn secondary', danger: 'btn danger' } as const
const ICON: Partial<Record<ActionKey, string>> = { delete: 'trash', stop: 'stop', discard: 'discard' }
const HINT: Partial<Record<ActionKey, string>> = {
  delete: '刪掉整個旅程，無法復原',
  stop: '不再接新客戶，進行中的客戶會中止',
  discard: '放棄草稿的修改，上線版本不變',
}

function MoreMenu({ specs, reasonId, onAct }: { specs: ActionSpec[]; reasonId: string; onAct: (k: ActionKey) => void }) {
  const [open, setOpen] = useState(false)
  const btn = useRef<HTMLButtonElement>(null)
  const box = useRef<HTMLDivElement>(null)
  const menuId = useId()

  useEffect(() => {
    if (!open) return
    box.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
    const items = () => [...(box.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        setOpen(false)
        btn.current?.focus()
      } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        const list = items()
        const at = list.indexOf(document.activeElement as HTMLElement)
        const next = e.key === 'ArrowDown' ? (at + 1) % list.length : (at - 1 + list.length) % list.length
        list[next]?.focus()
      } else if (e.key === 'Tab') setOpen(false)
    }
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node
      if (!box.current?.contains(t) && !btn.current?.contains(t)) setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
  }, [open])

  return (
    <>
      <button
        ref={btn}
        type="button"
        className="btn secondary icon"
        aria-label="更多動作"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="more" />
      </button>
      {open && (
        <div className="pm" role="menu" id={menuId} aria-label="更多動作" ref={box}>
          {specs.map((s) => {
            const off = !!s.disabled
            return (
              <button
                key={s.key}
                type="button"
                role="menuitem"
                className={`it${s.kind === 'danger' ? ' danger' : ''}`}
                aria-disabled={off ? true : undefined}
                aria-describedby={off ? reasonId : undefined}
                onClick={() => {
                  if (off) return
                  setOpen(false)
                  btn.current?.focus()
                  onAct(s.key)
                }}
              >
                <span className="l">
                  <Icon name={ICON[s.key] ?? 'other'} size={18} />
                  {s.label}
                </span>
                {HINT[s.key] && <small>{HINT[s.key]}</small>}
              </button>
            )
          })}
        </div>
      )}
    </>
  )
}

export function HeaderActions({
  specs,
  reasonId,
  busy = false,
  extra,
  onAct,
}: {
  specs: ActionSpec[]
  reasonId: string
  busy?: boolean
  /** 主要按鈕後面的連結（例：看送審內容） */
  extra?: ReactNode
  onAct: (key: ActionKey) => void
}) {
  const main = specs.filter((s) => !s.more)
  const more = specs.filter((s) => s.more)
  return (
    <div className="jh-acts">
      {main.map((s) => {
        const off = !!s.disabled
        return (
          <button
            key={s.key}
            type="button"
            className={CLASS[s.kind]}
            aria-disabled={off || busy ? true : undefined}
            aria-describedby={off ? reasonId : undefined}
            onClick={() => {
              if (off || busy) return
              onAct(s.key)
            }}
          >
            {s.label}
          </button>
        )
      })}
      {extra}
      {more.length > 0 && <MoreMenu specs={more} reasonId={reasonId} onAct={onAct} />}
    </div>
  )
}
