import { useState } from 'react'
import type { EnrollmentDetail } from '../../api/types'
import { Icon } from './Icons'
import { diagnosticText, type SummaryVM } from './model'

const ICONS = ['pin', 'next', 'mail']

/** 首屏三問摘要卡（role=status，左側色條＝狀態色） */
export function SummaryCard({ vm, d, now }: { vm: SummaryVM; d: EnrollmentDetail; now: Date }) {
  const [copied, setCopied] = useState(false)
  function copy() {
    void navigator.clipboard?.writeText(diagnosticText(d, now)).then(
      () => setCopied(true),
      () => undefined,
    )
  }
  return (
    <section className={`psum t-${vm.tone}`} role="status" aria-label="目前進度摘要">
      {vm.rows.map((r, i) => (
        <div className="srow" key={r.q}>
          <span className="sicon">
            <Icon name={ICONS[i]} />
          </span>
          <div>
            <h3 className="q">{r.q}</h3>
            <p className="a">
              <strong>{r.strong}</strong>
              {r.rest}
            </p>
            {r.hint && <p className="hint">{r.hint}</p>}
            {i === 2 && vm.diagnostic && (
              <p>
                <button className="btn secondary sm" type="button" onClick={copy}>
                  <Icon name="copy" size={16} />
                  {copied ? '已複製' : '複製診斷資訊'}
                </button>{' '}
                <span className="hint">內含進度編號與時間，不含客戶個資，可貼給技術人員。</span>
              </p>
            )}
          </div>
        </div>
      ))}
    </section>
  )
}
