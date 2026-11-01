import { Icon } from './Icons'
import type { StepVM } from './model'

export function Timeline({ steps }: { steps: StepVM[] }) {
  return (
    <>
      <h2 className="sec">走過的步驟</h2>
      <ol className="steps2" aria-label="這位客戶在旅程中的步驟，由舊到新">
        {steps.map((s) => (
          <li key={s.key} className={`step ${s.kind === 'stopped' ? 'skip' : s.kind}`} aria-current={s.kind === 'now' ? 'step' : undefined}>
            <span className="dot">
              <Icon name={s.icon} size={18} />
            </span>
            <div className="sbody">
              <div className="stitle">
                <strong>{s.title}</strong>
                <span className="slab">{s.label}</span>
              </div>
              {s.desc && <p className="sdesc">{s.desc}</p>}
              {s.time && <p className="stime muted">{s.time}</p>}
            </div>
          </li>
        ))}
      </ol>
    </>
  )
}
