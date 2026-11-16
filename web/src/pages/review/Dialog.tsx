// 對話框外殼：焦點進第一個欄位、Esc 取消、Tab 不出框、關閉後焦點回到觸發的按鈕
import { useEffect, useId, useRef, type ReactNode } from 'react'

const FOCUSABLE = 'button:not([aria-disabled="true"]):not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), a[href]'

export function Dialog({
  title,
  onClose,
  danger = false,
  children,
}: {
  title: string
  onClose: () => void
  danger?: boolean
  children: ReactNode
}) {
  const titleId = useId()
  const box = useRef<HTMLDivElement>(null)
  const close = useRef(onClose)
  useEffect(() => {
    close.current = onClose
  })

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    const el = box.current
    const first = el?.querySelector<HTMLElement>('[data-autofocus]') ?? el?.querySelector<HTMLElement>(FOCUSABLE)
    first?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        close.current()
        return
      }
      if (e.key !== 'Tab' || !el) return
      const items = [...el.querySelectorAll<HTMLElement>(FOCUSABLE)]
      if (items.length === 0) return
      const a = document.activeElement
      if (e.shiftKey && (a === items[0] || !el.contains(a))) {
        e.preventDefault()
        items[items.length - 1].focus()
      } else if (!e.shiftKey && (a === items[items.length - 1] || !el.contains(a))) {
        e.preventDefault()
        items[0].focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      opener?.focus?.()
    }
  }, [])

  return (
    <div className="dlg-overlay">
      <div
        className={`dlg${danger ? ' danger' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        ref={box}
      >
        <h2 id={titleId} className="dlg-title">
          {title}
        </h2>
        {children}
      </div>
    </div>
  )
}

/** 多行文字欄位＋字數；maxLength 一律 200（契約 §3.2） */
export function TextField({
  label,
  value,
  onChange,
  autoFocus = false,
  error,
  max = 200,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  autoFocus?: boolean
  error?: string | null
  max?: number
}) {
  const id = useId()
  return (
    <div className="field dlg-field">
      <label htmlFor={id}>{label}</label>
      <textarea
        id={id}
        rows={3}
        maxLength={max}
        value={value}
        data-autofocus={autoFocus ? '' : undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-err` : undefined}
        onChange={(e) => onChange(e.target.value)}
      />
      <span className="hint">
        {value.length} / {max}
      </span>
      {error && (
        <span id={`${id}-err`} className="err-text" role="alert">
          {error}
        </span>
      )}
    </div>
  )
}
