// 右側節點屬性面板：名稱、各型別欄位、下一步（連線）、刪除。欄位標籤同設計稿 ed-*。
import { useId, useState, type ReactNode } from 'react'
import type { FieldKeys } from '../api/types'
import {
  buildDuration,
  condOf,
  displayName,
  eventFieldLabel,
  fieldLabel,
  incomingOf,
  isGroup,
  linkTargets,
  MAX_COND_DEPTH,
  OPERATORS,
  outputSpecs,
  parseCondValue,
  condValueText,
  parseDuration,
  targetOfPort,
  typeLabel,
  UNIT_LABEL,
  variantsOf,
  type Cond,
  type CondGroup,
  type CondLeaf,
  type Def,
  type DefNode,
  type DurationUnit,
  type Variant,
} from './model'

export function TextField({
  label,
  value,
  onChange,
  hint,
  multiline,
  maxLength,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  hint?: string
  multiline?: boolean
  maxLength?: number
}) {
  const id = useId()
  const hid = `${id}-h`
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {multiline ? (
        <textarea id={id} rows={3} value={value} maxLength={maxLength} aria-describedby={hint ? hid : undefined} onChange={(e) => onChange(e.target.value)} />
      ) : (
        <input id={id} type="text" value={value} maxLength={maxLength} aria-describedby={hint ? hid : undefined} onChange={(e) => onChange(e.target.value)} />
      )}
      {hint && (
        <span className="hint" id={hid}>
          {hint}
        </span>
      )}
    </div>
  )
}

function SelectField({
  label,
  value,
  onChange,
  children,
  hint,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  children: ReactNode
  hint?: string
}) {
  const id = useId()
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        {children}
      </select>
      {hint && <span className="hint">{hint}</span>}
    </div>
  )
}

/** 數量＋單位（分鐘／小時／天）；輸入中的文字留在本地，合法時才寫回 Duration */
function DurationField({
  amountLabel,
  value,
  onChange,
}: {
  amountLabel: string
  value: string | null
  onChange: (v: string | null) => void
}) {
  const parsed = parseDuration(value)
  const [text, setText] = useState(parsed ? String(parsed.amount) : '')
  const [unit, setUnit] = useState<DurationUnit>(parsed?.unit ?? 'H')
  const push = (t: string, u: DurationUnit) => {
    const n = Number(t)
    onChange(/^\d+$/.test(t) && n > 0 ? buildDuration(n, u) : null)
  }
  return (
    <div className="row2">
      <TextField
        label={amountLabel}
        value={text}
        onChange={(t) => {
          setText(t)
          push(t, unit)
        }}
      />
      <SelectField
        label="單位"
        value={unit}
        onChange={(u) => {
          setUnit(u as DurationUnit)
          push(text, u as DurationUnit)
        }}
      >
        {(Object.keys(UNIT_LABEL) as DurationUnit[]).map((u) => (
          <option key={u} value={u}>
            {UNIT_LABEL[u]}
          </option>
        ))}
      </SelectField>
    </div>
  )
}

// ---------- 條件樹 ----------
const CUSTOM = '__custom__'

function LeafRow({
  leaf,
  keys,
  onChange,
  onRemove,
}: {
  leaf: CondLeaf
  keys: FieldKeys | null
  onChange: (l: CondLeaf) => void
  onRemove: () => void
}) {
  const known = new Set([...(keys?.profile_attributes ?? []), ...(keys?.event_properties ?? []).map((k) => `trigger.properties.${k}`)])
  const [custom, setCustom] = useState(false)
  const [text, setText] = useState(condValueText(leaf.value))
  const op = OPERATORS.find((o) => o.value === leaf.operator)
  const setField = (f: string) => onChange({ ...leaf, field: f })
  return (
    <li className="cond-row">
      <div className="cond-line">
        <select
          aria-label="欄位"
          value={custom ? CUSTOM : leaf.field}
          onChange={(e) => {
            if (e.target.value === CUSTOM) {
              setCustom(true)
              setField('')
            } else {
              setCustom(false)
              setField(e.target.value)
            }
          }}
        >
          <option value="">（選擇欄位）</option>
          {leaf.field && !custom && !known.has(leaf.field) && <option value={leaf.field}>{fieldLabel(leaf.field)}</option>}
          {(keys?.profile_attributes ?? []).map((k) => (
            <option key={k} value={k}>
              {fieldLabel(k)}
            </option>
          ))}
          {(keys?.event_properties ?? []).map((k) => (
            <option key={`e-${k}`} value={`trigger.properties.${k}`}>
              {eventFieldLabel(k)}
            </option>
          ))}
          <option value={CUSTOM}>手動輸入…</option>
        </select>
        <select
          aria-label="比較"
          value={leaf.operator}
          onChange={(e) => {
            const operator = e.target.value
            onChange({ ...leaf, operator, value: OPERATORS.find((o) => o.value === operator)?.noValue ? undefined : parseCondValue(operator, text) })
          }}
        >
          {OPERATORS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
        {!op?.noValue && (
          <input
            aria-label="值"
            type="text"
            value={text}
            onChange={(e) => {
              setText(e.target.value)
              onChange({ ...leaf, value: parseCondValue(leaf.operator, e.target.value) })
            }}
          />
        )}
        <button type="button" className="btn ghost-dark sm" aria-label="移除這個條件" onClick={onRemove}>
          移除
        </button>
      </div>
      {custom && (
        <input
          aria-label="欄位代碼"
          type="text"
          placeholder="例：tier 或 trigger.properties.cart_total"
          value={leaf.field}
          onChange={(e) => setField(e.target.value)}
        />
      )}
      {leaf.field && (
        <details className="adv">
          <summary>進階資訊</summary>
          <p className="mono">欄位代碼：{leaf.field}</p>
        </details>
      )}
    </li>
  )
}

const GROUP_OPS: { op: CondGroup['op']; label: string }[] = [
  { op: 'AND', label: '全部符合' },
  { op: 'OR', label: '任一符合' },
  { op: 'NOT', label: '但不可以是…' },
]

function GroupEditor({
  group,
  depth,
  keys,
  onChange,
  onRemove,
}: {
  group: CondGroup
  depth: number
  keys: FieldKeys | null
  onChange: (g: CondGroup) => void
  onRemove?: () => void
}) {
  const setChild = (i: number, c: Cond | null) =>
    onChange({ ...group, children: c ? group.children.map((x, j) => (j === i ? c : x)) : group.children.filter((_, j) => j !== i) })
  return (
    <div className="cond-group">
      <div className="seg" role="group" aria-label="符合方式">
        {GROUP_OPS.map((o) => (
          <button key={o.op} type="button" className="btn secondary sm" aria-pressed={group.op === o.op} onClick={() => onChange({ ...group, op: o.op })}>
            {o.label}
          </button>
        ))}
        {onRemove && (
          <button type="button" className="btn ghost-dark sm" onClick={onRemove}>
            移除群組
          </button>
        )}
      </div>
      <ul className="cond-list">
        {group.children.map((c, i) =>
          isGroup(c) ? (
            <li key={i}>
              <GroupEditor group={c} depth={depth + 1} keys={keys} onChange={(g) => setChild(i, g)} onRemove={() => setChild(i, null)} />
            </li>
          ) : (
            <LeafRow key={i} leaf={c} keys={keys} onChange={(l) => setChild(i, l)} onRemove={() => setChild(i, null)} />
          ),
        )}
      </ul>
      <div className="cond-add">
        <button
          type="button"
          className="btn secondary sm"
          onClick={() => onChange({ ...group, children: [...group.children, { type: 'attribute', field: '', operator: 'EQ', value: '' }] })}
        >
          加入條件
        </button>
        {depth < MAX_COND_DEPTH && (
          <button type="button" className="btn secondary sm" onClick={() => onChange({ ...group, children: [...group.children, { op: 'AND', children: [] }] })}>
            加入群組
          </button>
        )}
      </div>
    </div>
  )
}

// ---------- 各型別欄位 ----------
function Body({
  node,
  def,
  keys,
  set,
  openSettings,
}: {
  node: DefNode
  def: Def
  keys: FieldKeys | null
  set: (fn: (n: DefNode) => DefNode) => void
  openSettings: () => void
}) {
  const cfg = (patch: Record<string, unknown>) => set((n) => ({ ...n, config: { ...n.config, ...patch } }))
  const str = (k: string) => (typeof node.config[k] === 'string' ? (node.config[k] as string) : '')
  switch (node.type) {
    case 'EventSourceNode':
      return <TextField label="事件名稱" value={str('event_name')} onChange={(v) => cfg({ event_name: v })} hint="客戶做了這件事就進入旅程；小寫英文與底線，例如 cart_item_added" />
    case 'SendMessageNode':
      return (
        <>
          <TextField label="管道代碼" value={str('channel_id')} onChange={(v) => cfg({ channel_id: v })} hint="1 到 100 字，只能用英數字與 . _ : -。目前一律走測試管道，不會真的送出" />
          <TextField label="範本代碼" value={str('template_id')} onChange={(v) => cfg({ template_id: v })} hint="1 到 100 字，只能用英數字與 . _ : -。範本清單與預覽之後才有" />
        </>
      )
    case 'WaitDelayNode':
      return <DurationField amountLabel="時間" value={str('duration') || null} onChange={(v) => cfg({ duration: v ?? undefined })} />
    case 'WaitForEventNode': {
      const unlimited = node.config.timeout === null
      return (
        <>
          <TextField label="等這個事件" value={str('event_name')} onChange={(v) => cfg({ event_name: v })} hint="事件名稱，小寫英文與底線" />
          {!unlimited && <DurationField amountLabel="最多等" value={str('timeout') || null} onChange={(v) => cfg({ timeout: v ?? undefined })} />}
          <div className="check-row">
            <label>
              <input
                type="checkbox"
                checked={unlimited}
                onChange={(e) => cfg({ timeout: e.target.checked ? null : 'P1D' })}
              />{' '}
              不設時限，一直等到事件發生
            </label>
          </div>
        </>
      )
    }
    case 'ConditionNode':
      return (
        <div className="field">
          <p className="sub">符合這些條件的客戶走「是」</p>
          <GroupEditor group={condOf(node)} depth={1} keys={keys} onChange={(g) => cfg({ condition: g })} />
          <span className="hint">最多 5 層。欄位選單列出客戶屬性與這個事件的屬性（取自最近的資料，不一定完整，也可手動輸入）。</span>
        </div>
      )
    case 'SplitTestNode':
      return <SplitEditor node={node} cfg={cfg} />
    case 'MergeNode': {
      const inc = incomingOf(def, node.id)
      return (
        <p>
          不需要設定。目前有 <strong>{inc.length} 條路</strong>進來{inc.length ? `（${inc.map(displayName).join('、')}）` : ''}，會合後每位客戶只會往下走一次。
        </p>
      )
    }
    case 'GoalNode':
      return (
        <>
          <TextField label="達標目標" value={str('goal_id')} onChange={(v) => cfg({ goal_id: v })} hint="目標代碼，供成效辨識；旅程層級的達標事件在「旅程設定」" />
          <p>
            <button type="button" className="btn secondary sm" onClick={openSettings}>
              前往旅程設定
            </button>
          </p>
          <p className="muted">這個節點沒有下一步</p>
        </>
      )
    case 'ExitNode': {
      const reason = str('reason')
      return (
        <TextField
          label="退出原因（1 到 200 字）"
          value={reason}
          maxLength={200}
          onChange={(v) => cfg({ reason: v })}
          hint={`會顯示在這位客戶的旅程進度裡（${reason.length} / 200）`}
        />
      )
    }
    default:
      return null
  }
}

function SplitEditor({ node, cfg }: { node: DefNode; cfg: (p: Record<string, unknown>) => void }) {
  const vs = variantsOf(node)
  const total = vs.reduce((n, v) => n + (Number(v.weight) || 0), 0)
  const setV = (i: number, patch: Partial<Variant>) => cfg({ variants: vs.map((v, j) => (j === i ? { ...v, ...patch } : v)) })
  const used = new Set(vs.map((v) => v.port))
  const nextPort = () => {
    for (let i = 1; ; i++) if (!used.has(`v${i}`)) return `v${i}`
  }
  return (
    <fieldset className="field split">
      <legend>分組（2 到 10 組，權重合計要 100）</legend>
      {vs.map((v, i) => (
        <div className="row2" key={v.port}>
          <label>
            組名{v.is_control ? '（對照組）' : ''}
            <input type="text" value={v.label} onChange={(e) => setV(i, { label: e.target.value })} />
          </label>
          <label>
            權重 %
            <input
              type="text"
              inputMode="numeric"
              value={String(v.weight ?? '')}
              onChange={(e) => setV(i, { weight: e.target.value === '' ? 0 : Number(e.target.value.replace(/\D/g, '')) })}
            />
          </label>
          {vs.length > 2 && (
            <button type="button" className="btn ghost-dark sm" onClick={() => cfg({ variants: vs.filter((_, j) => j !== i) })}>
              移除
            </button>
          )}
        </div>
      ))}
      <p className={total === 100 ? 'muted' : 'warn-text'} role="status">
        合計 {total}%{total === 100 ? '' : `，請調整到剛好 100%。`}
      </p>
      {vs.length < 10 && (
        <button type="button" className="btn secondary sm" onClick={() => cfg({ variants: [...vs, { port: nextPort(), label: `組別 ${vs.length + 1}`, weight: 0 }] })}>
          新增分組
        </button>
      )}
      <span className="hint">同一位客戶每次都分到同一組（穩定分組）。每一組各連一個下一步。</span>
    </fieldset>
  )
}

export function NodePanel({
  node,
  def,
  keys,
  onChange,
  onConnect,
  onDelete,
  onClose,
  openSettings,
}: {
  node: DefNode
  def: Def
  keys: FieldKeys | null
  onChange: (fn: (n: DefNode) => DefNode) => void
  onConnect: (port: string, target: string) => void
  onDelete: () => void
  onClose: () => void
  openSettings: () => void
}) {
  const hid = useId()
  const targets = linkTargets(def, node.id)
  const specs = outputSpecs(node)
  return (
    <aside className="side-panel" aria-labelledby={hid}>
      <div className="ph">
        <div>
          <h2 id={hid}>{displayName(node)}</h2>
          <p className="muted">{typeLabel(node.type)}</p>
        </div>
        <button type="button" className="btn secondary sm" onClick={onClose}>
          關閉面板
        </button>
      </div>
      <TextField label="名稱" value={node.name ?? ''} onChange={(v) => onChange((n) => ({ ...n, name: v }))} />
      <Body node={node} def={def} keys={keys} set={onChange} openSettings={openSettings} />
      {specs.length > 0 && (
        <>
          <h3 className="sub">下一步</h3>
          {specs.map((s) => (
            <SelectField key={s.port} label={s.label} value={targetOfPort(node, s.port)} onChange={(v) => onConnect(s.port, v)} hint={s.optional ? '選填。不連線時，這位客戶的旅程進度就此結束' : undefined}>
              <option value="">{s.optional ? '不處理，旅程結束' : '（尚未連線）'}</option>
              {targets.map((t) => (
                <option key={t.id} value={t.id}>
                  {displayName(t)}
                </option>
              ))}
            </SelectField>
          ))}
        </>
      )}
      {node.type !== 'StartNode' && (
        <p>
          <button type="button" className="btn danger" onClick={onDelete}>
            刪除節點
          </button>
        </p>
      )}
    </aside>
  )
}
