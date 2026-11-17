// 頁首動作鈕：停用鈕保留（aria-disabled，可聚焦），原因寫在按鈕下方並以 aria-describedby 連結
import { useId } from 'react'
import type { ActionKey, ActionSpec } from './model'

const CLASS = { primary: 'btn', secondary: 'btn secondary', danger: 'btn danger' } as const

export function ActionButtons({
  specs,
  busy = false,
  onAct,
}: {
  specs: ActionSpec[]
  busy?: boolean
  onAct: (key: ActionKey) => void
}) {
  const uid = useId()
  return (
    <>
      {specs.map((s) => {
        const rid = `${uid}-${s.key}`
        const off = !!s.disabled
        return (
          <span className="act" key={s.key}>
            <button
              type="button"
              className={CLASS[s.kind]}
              aria-disabled={off || busy ? true : undefined}
              aria-describedby={off ? rid : undefined}
              onClick={() => {
                if (off || busy) return
                onAct(s.key)
              }}
            >
              {s.label}
            </button>
            {off && (
              <span id={rid} className="act-reason">
                {s.disabled}
              </span>
            )}
          </span>
        )
      })}
    </>
  )
}
