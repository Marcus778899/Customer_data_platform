// 「旅程設定」面板：名稱、描述、重新進入（五選一）、達標、隨時退出（spec 03 §3；journeys.md §4）
import { useId, useState } from 'react'
import { OPERATORS, parseCondValue, withSettings, type Def } from './model'
import { TextField } from './NodePanel'

const PREFIX = '$.trigger.properties.'
const MODES: { mode: string; label: string; hint: string }[] = [
  { mode: 'DEDUP_KEY', label: '同一筆購物車只進入一次', hint: '用下方「判斷用的欄位」判斷。同一個值再送幾次，都只建立一筆旅程進度' },
  { mode: 'ONCE', label: '每位客戶只進入一次', hint: '之後不論什麼事件都不會再進入' },
  { mode: 'COOLDOWN', label: '隔一段時間才可再進入', hint: '例如 30 天內不重複進入' },
  { mode: 'DEDUP_KEY_WITH_COOLDOWN', label: '同一筆只進入一次，且要隔一段時間', hint: '兩個條件都要符合' },
  { mode: 'UNLIMITED', label: '不限制', hint: '每次事件都進入（可能重複打擾）' },
]

interface Reentry {
  mode?: string
  dedup_key_path?: string
  cooldown?: string
}
interface ExitCond {
  type: 'event' | 'attribute'
  event?: string
  since?: string
  field?: string
  operator?: string
  value?: unknown
  on_match?: string
}

export function SettingsPanel({ def, onChange, onClose }: { def: Def; onChange: (fn: (d: Def) => Def) => void; onClose: () => void }) {
  const hid = useId()
  const reentry = (def.settings.reentry ?? { mode: 'UNLIMITED' }) as Reentry
  const mode = reentry.mode ?? 'UNLIMITED'
  const goal = def.settings.goal as { goal_id?: string; condition?: { event?: string } } | undefined
  const gx = (def.settings.global_exit ?? { conditions: [], on_match: 'EXITED' }) as { conditions?: ExitCond[]; on_match?: string }
  const conds = gx.conditions ?? []
  const key = reentry.dedup_key_path?.startsWith(PREFIX) ? reentry.dedup_key_path.slice(PREFIX.length) : (reentry.dedup_key_path ?? '')
  const cooldown = /^P(\d+)D$/.exec(reentry.cooldown ?? '')?.[1] ?? ''
  const [days, setDays] = useState(cooldown)

  const setReentry = (patch: Reentry) =>
    onChange((d) => withSettings(d, (s) => ({ ...s, reentry: { ...((s.reentry as Reentry) ?? {}), ...patch } })))
  const pickMode = (m: string) =>
    onChange((d) =>
      withSettings(d, (s) => {
        const r: Reentry = { mode: m }
        const old = (s.reentry as Reentry) ?? {}
        if (m.includes('DEDUP_KEY') && old.dedup_key_path) r.dedup_key_path = old.dedup_key_path
        if (m.includes('COOLDOWN') && old.cooldown) r.cooldown = old.cooldown
        return { ...s, reentry: r }
      }),
    )
  const setConds = (next: ExitCond[]) =>
    onChange((d) => withSettings(d, (s) => ({ ...s, global_exit: { on_match: 'EXITED', ...((s.global_exit as object) ?? {}), conditions: next } })))
  const setCond = (i: number, patch: Partial<ExitCond>) => setConds(conds.map((c, j) => (j === i ? { ...c, ...patch } : c)))

  return (
    <aside className="side-panel" aria-labelledby={hid}>
      <div className="ph">
        <div>
          <h2 id={hid}>旅程設定</h2>
          <p className="muted">整個旅程共用的規則</p>
        </div>
        <button type="button" className="btn secondary sm" onClick={onClose}>
          完成
        </button>
      </div>
      <TextField label="旅程名稱" value={def.name} onChange={(v) => onChange((d) => ({ ...d, name: v }))} />
      <TextField label="描述（選填）" multiline value={def.description ?? ''} onChange={(v) => onChange((d) => ({ ...d, description: v === '' ? null : v }))} />

      <fieldset className="field">
        <legend>同一位客戶可以再次進入嗎？</legend>
        {MODES.map((m) => (
          <div className="radio-row" key={m.mode}>
            <label>
              <input type="radio" name="reentry" checked={mode === m.mode} aria-describedby={`${hid}-${m.mode}`} onChange={() => pickMode(m.mode)} /> {m.label}
            </label>
            <span className="hint" id={`${hid}-${m.mode}`}>
              {m.hint}
            </span>
          </div>
        ))}
      </fieldset>
      {mode.includes('DEDUP_KEY') && (
        <TextField
          label="判斷用的欄位"
          value={key}
          onChange={(v) => setReentry({ dedup_key_path: v ? `${PREFIX}${v}` : undefined })}
          hint="事件屬性鍵，小寫英文與底線，例如 cart_id"
        />
      )}
      {mode.includes('COOLDOWN') && (
        <TextField
          label="間隔天數"
          value={days}
          onChange={(v) => {
            setDays(v)
            setReentry({ cooldown: /^\d+$/.test(v) && Number(v) > 0 ? `P${v}D` : undefined })
          }}
          hint="客戶上一次旅程結束後，隔幾天才可再進入"
        />
      )}

      <h3 className="sub">達標</h3>
      <TextField
        label="達標事件"
        value={goal?.condition?.event ?? ''}
        hint="客戶做了這件事，算達標（旅程進度以「已達標」結束）"
        onChange={(v) =>
          onChange((d) =>
            withSettings(d, (s) => {
              if (!v) {
                const rest = { ...s }
                delete rest.goal
                return rest
              }
              return { ...s, goal: { goal_id: goal?.goal_id || v, condition: { type: 'event', event: v } } }
            }),
          )
        }
      />

      <h3 className="sub">隨時退出（任何一步、含等待中都有效）</h3>
      <ul className="cond-list">
        {conds.map((c, i) => (
          <li className="cond-row" key={i}>
            {c.type === 'event' ? (
              <>
                <TextField label={`退出事件 ${i + 1}`} value={c.event ?? ''} onChange={(v) => setCond(i, { event: v })} />
                <div className="seg" role="group" aria-label={`退出條件 ${i + 1} 的結果`}>
                  {[
                    ['GOAL_MET', '視為達標'],
                    ['EXITED', '退出'],
                  ].map(([v, t]) => (
                    <button key={v} type="button" className="btn secondary sm" aria-pressed={(c.on_match ?? gx.on_match ?? 'EXITED') === v} onClick={() => setCond(i, { on_match: v })}>
                      {t}
                    </button>
                  ))}
                </div>
              </>
            ) : (
              <div className="cond-line">
                <input aria-label={`退出屬性 ${i + 1}`} type="text" value={c.field ?? ''} placeholder="客戶屬性，例：marketing_opt_out" onChange={(e) => setCond(i, { field: e.target.value })} />
                <select aria-label={`退出比較 ${i + 1}`} value={c.operator ?? 'EQ'} onChange={(e) => setCond(i, { operator: e.target.value })}>
                  {OPERATORS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
                <input
                  aria-label={`退出值 ${i + 1}`}
                  type="text"
                  defaultValue={String(c.value ?? '')}
                  onChange={(e) => setCond(i, { value: e.target.value === 'true' ? true : e.target.value === 'false' ? false : parseCondValue(c.operator ?? 'EQ', e.target.value) })}
                />
              </div>
            )}
            <button type="button" className="btn ghost-dark sm" onClick={() => setConds(conds.filter((_, j) => j !== i))}>
              移除這個退出條件
            </button>
          </li>
        ))}
      </ul>
      <div className="cond-add">
        <button type="button" className="btn secondary sm" onClick={() => setConds([...conds, { type: 'event', event: '', since: 'ENROLLMENT', on_match: 'EXITED' }])}>
          加入退出事件
        </button>
        <button type="button" className="btn secondary sm" onClick={() => setConds([...conds, { type: 'attribute', field: '', operator: 'EQ', value: '' }])}>
          加入客戶屬性條件
        </button>
      </div>
    </aside>
  )
}
