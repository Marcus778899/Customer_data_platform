// 事件篩選編輯區塊（進入條件、等事件面板）：一行一句的條件、白話摘要、清除後可復原、欄位建議載入與失敗。
// 版型沿用條件節點；規格見 docs/ui/journey-builder.md「事件篩選」，契約 docs/contracts/journeys.md §4。
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { getFieldKeys } from '../api/client'
import type { Issue } from '../api/types'
import {
  FIELD_PREFIX,
  MAX_DEPTH,
  blockSummary,
  countIncomplete,
  countLeaves,
  emptyGroup,
  emptyLeaf,
  filterIssues,
  fieldKind,
  isNumericField,
  keyLabel,
  needsKindToggle,
  numericInput,
  unknownKindHint,
  textLooksNumber,
  valueKindLabel,
  leafField,
  leafInlineErrors,
  issuesAt,
  mapTree,
  opNoValue,
  toWire,
  treeFromWire,
  wireKey,
  type FGroup,
  type FLeaf,
  type FNode,
  type FUnsup,
} from './eventFilter'
import { useLocked } from './lock'
import { KindToggle, OperatorOptions } from './ValueControls'

// ---------- 欄位建議（field-keys） ----------
type KeysState = { status: 'idle' | 'loading' | 'ready' | 'failed'; keys: string[] }

/** 以事件名稱載入欄位建議；名稱改變時延遲 400ms 重載，3 秒沒回視為失敗（改手動輸入），晚到的回應仍會採用 */
export function useEventKeys(event: string): KeysState & { retry: () => void } {
  const [attempt, setAttempt] = useState(0)
  const [res, setRes] = useState<{ k: string; status: 'ready' | 'failed'; keys: string[] } | null>(null)
  const prev = useRef<string | null>(null)
  const k = `${event}#${attempt}`
  useEffect(() => {
    if (!event) {
      prev.current = event
      return
    }
    let cancelled = false
    const delay = prev.current === null || prev.current === event ? 0 : 400
    prev.current = event
    const t = window.setTimeout(() => {
      getFieldKeys(event).then(
        (r) => !cancelled && setRes({ k, status: 'ready', keys: r.event_properties }),
        () => !cancelled && setRes({ k, status: 'failed', keys: [] }),
      )
    }, delay)
    const slow = window.setTimeout(() => !cancelled && setRes((r) => (r?.k === k ? r : { k, status: 'failed', keys: [] })), delay + 3000)
    return () => {
      cancelled = true
      window.clearTimeout(t)
      window.clearTimeout(slow)
    }
  }, [event, k])
  const retry = () => setAttempt((n) => n + 1)
  if (!event) return { status: 'idle', keys: [], retry }
  if (res?.k === k) return { status: res.status, keys: res.keys, retry }
  return { status: 'loading', keys: [], retry }
}

// ---------- 小元件 ----------
const CUSTOM = '__custom__'

function Icon({ d }: { d: string }) {
  return (
    <svg className="i sm" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  )
}
const X_PATH = 'M18 6 6 18M6 6l12 12'

function Err({ id, messages }: { id: string; messages: string[] }) {
  if (!messages.length) return null
  return (
    <p className="ferr" role="alert" id={id}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
        <circle cx="12" cy="12" r="10" />
        <path d="M12 8v4M12 16h.01" />
      </svg>
      <span>{messages.join(' ')}</span>
    </p>
  )
}

interface Ctx {
  keys: KeysState
  issues: { path: string; message: string }[]
  paths: Map<string, string>
  locked: boolean
  update: (id: string, fn: (n: FNode) => FNode | null) => void
  remove: (id: string) => void
  clear: () => void
}

function LeafRow({ leaf, ctx }: { leaf: FLeaf; ctx: Ctx }) {
  const uid = useId()
  const { keys, locked } = ctx
  const field = leafField(leaf)
  const custom = leaf.customKey !== null
  const inline = leafInlineErrors(leaf)
  const iss = issuesAt(ctx.issues, ctx.paths.get(leaf.id))
  const fieldErrs = [...(inline.field ? [inline.field] : []), ...iss.field]
  const valueErrs = [...(inline.value ? [inline.value] : []), ...iss.value]
  const rowErrs = [...iss.row, ...iss.operator]
  const bad = fieldErrs.length + valueErrs.length + rowErrs.length > 0
  const set = (patch: Partial<FLeaf>) => ctx.update(leaf.id, (n) => ({ ...(n as FLeaf), ...patch, raw: undefined }))
  const noValue = opNoValue(leaf.operator)
  const textHint = textLooksNumber(leaf, field)
  const loadingEmpty = keys.status === 'loading' && !field && !custom
  const feId = `${uid}-fe`
  const veId = `${uid}-ve`
  const reId = `${uid}-re`
  const describe = (ids: string[], on: boolean) => (on ? ids.join(' ') : undefined)

  const known = new Set(keys.keys.map((k) => `${FIELD_PREFIX}${k}`))
  const manualInput = (
    <div className="pfx">
      <span aria-hidden="true">{FIELD_PREFIX}</span>
      <input
        type="text"
        aria-label="欄位代碼"
        value={leaf.customKey ?? ''}
        readOnly={locked}
        placeholder="例：cart_total"
        aria-invalid={fieldErrs.length ? true : undefined}
        aria-describedby={describe([feId], fieldErrs.length > 0)}
        onChange={(e) => set({ customKey: e.target.value, field: '', num: isNumericField(`${FIELD_PREFIX}${e.target.value}`) })}
      />
    </div>
  )

  // 各元件固定在同一個位置：輸入中途 custom 狀態改變時，欄位代碼輸入框不會被重建而失去焦點
  const noField = !custom && !leaf.field
  const showSkel = noField && keys.status === 'loading'
  const showIdle = noField && keys.status === 'idle'
  const showManual = custom || (noField && keys.status === 'failed')
  const showSelect = !showSkel && !showIdle && !(keys.status === 'failed' && !leaf.field)
  const fieldControl = (
    <>
      {showSkel ? <span className="skel" role="status" aria-label="正在載入欄位建議" /> : null}
      {showIdle ? (
        <div className="ef-idle">
          <span className="hint">先填上面的事件名稱，才能列出這個事件的欄位。</span>
          <button type="button" className="btn secondary sm" disabled={locked} onClick={() => set({ customKey: '', field: '', operator: '', text: '', num: false })}>
            手動輸入欄位代碼…
          </button>
        </div>
      ) : null}
      {showSelect ? (
        <select
          aria-label="欄位"
          value={custom ? CUSTOM : field}
          disabled={locked}
          aria-invalid={fieldErrs.length ? true : undefined}
          aria-describedby={describe([feId], fieldErrs.length > 0)}
          onChange={(e) => {
            const v = e.target.value
            if (v === CUSTOM) set({ customKey: '', field: '', operator: '', text: '', num: false })
            else set({ customKey: null, field: v, operator: '', text: '', num: isNumericField(v) })
          }}
        >
          <option value="">（選擇欄位）</option>
          {field && !custom && !known.has(field) && <option value={field}>{keyLabel(field.startsWith(FIELD_PREFIX) ? field.slice(FIELD_PREFIX.length) : field)}</option>}
          {keys.keys.map((k) => (
            <option key={k} value={`${FIELD_PREFIX}${k}`}>
              {keyLabel(k)}
            </option>
          ))}
          <option value={CUSTOM}>手動輸入欄位代碼…</option>
        </select>
      ) : null}
      {showManual ? manualInput : null}
    </>
  )

  return (
    <li className={`leaf cond-row${bad ? ' bad' : ''}`} data-fid={leaf.id}>
      <div className="sentence">
        <div className="sf">{fieldControl}</div>
        <select
          aria-label="比較"
          value={leaf.operator}
          disabled={locked || loadingEmpty}
          aria-invalid={iss.operator.length ? true : undefined}
          onChange={(e) => set({ operator: e.target.value })}
        >
          <option value="">（選擇比較）</option>
          <OperatorOptions field={field} current={leaf.operator} />
        </select>
        <input
          type="text"
          aria-label="值"
          value={noValue ? '' : leaf.text}
          readOnly={locked}
          disabled={noValue || loadingEmpty}
          inputMode={numericInput(leaf.operator, leaf.num) ? 'decimal' : undefined}
          placeholder={leaf.operator === 'IN' || leaf.operator === 'NOT_IN' ? '用逗號分隔，例：line, email' : undefined}
          aria-invalid={valueErrs.length ? true : undefined}
          aria-describedby={describe([veId], valueErrs.length > 0)}
          onChange={(e) => set({ text: e.target.value })}
        />
        <button type="button" className="x2 btn ghost-dark sm" aria-label="移除這個條件" disabled={locked} onClick={() => ctx.remove(leaf.id)}>
          <Icon d={X_PATH} />
        </button>
      </div>
      {needsKindToggle(field, leaf.operator) && <KindToggle num={leaf.num} disabled={locked} onChange={(num) => set({ num })} />}
      {field && !leaf.operator && fieldKind(field) === 'unknown' && (
        <p className="hint">{unknownKindHint(keyLabel(field.slice(FIELD_PREFIX.length)))}</p>
      )}
      <Err id={feId} messages={fieldErrs} />
      <Err id={veId} messages={valueErrs} />
      {textHint && <p className="hint">{textHint}</p>}
      <Err id={reId} messages={rowErrs} />
      {field && (
        <details className="adv">
          <summary>進階資訊</summary>
          <p className="mono">
            event_filter：{field}
            {leaf.operator ? ` ・ ${leaf.operator}` : ''}
            {!noValue && leaf.text ? ` ・ ${leaf.text}${valueKindLabel(leaf.operator, leaf.num)}` : ''}
          </p>
        </details>
      )}
    </li>
  )
}

function rawKind(raw: unknown): string {
  const t = typeof raw === 'object' && raw !== null ? (raw as { type?: unknown }).type : undefined
  return t === 'segment' ? '客群' : t === 'behavior' ? '行為' : typeof t === 'string' ? t : '未知'
}

function UnsupRow({ node, ctx }: { node: FUnsup; ctx: Ctx }) {
  const uid = useId()
  const iss = issuesAt(ctx.issues, ctx.paths.get(node.id))
  const msgs = [...iss.row, ...iss.field, ...iss.operator, ...iss.value]
  return (
    <li className={`leaf unsup${msgs.length ? ' bad' : ''}`} data-fid={node.id}>
      <div className="uhead">
        <strong>不支援的條件（{rawKind(node.raw)}）</strong>
        <span className="tagx">畫面無法編輯</span>
        <button type="button" className="x2 btn ghost-dark sm" aria-label="移除這個條件" disabled={ctx.locked} onClick={() => ctx.remove(node.id)}>
          <Icon d={X_PATH} />
        </button>
      </div>
      <Err id={`${uid}-e`} messages={msgs} />
    </li>
  )
}

const GROUP_OPS: { op: FGroup['op']; label: string }[] = [
  { op: 'AND', label: '全部符合' },
  { op: 'OR', label: '任一符合' },
  { op: 'NOT', label: '但不可以是…' },
]

function GroupView({ group, depth, ctx, root, onAdd }: { group: FGroup; depth: number; ctx: Ctx; root: boolean; onAdd: (gid: string, n: FNode) => void }) {
  const uid = useId()
  const iss = issuesAt(ctx.issues, ctx.paths.get(group.id))
  const ops = GROUP_OPS.filter((o) => o.op !== 'NOT' || !root || group.op === 'NOT')
  const setOp = (op: FGroup['op']) => ctx.update(group.id, (n) => ({ ...(n as FGroup), op }))
  return (
    <div className="cond-group" data-fid={group.id}>
      <div className="seg" role="group" aria-label="組合方式">
        {ops.map((o) => (
          <button key={o.op} type="button" className="btn secondary sm" aria-pressed={group.op === o.op} disabled={ctx.locked} onClick={() => setOp(o.op)}>
            {o.label}
          </button>
        ))}
        {!root && (
          <button type="button" className="btn ghost-dark sm" disabled={ctx.locked} onClick={() => ctx.remove(group.id)}>
            移除群組
          </button>
        )}
      </div>
      <Err id={`${uid}-e`} messages={[...iss.row, ...iss.field, ...iss.operator, ...iss.value]} />
      <ul className="cond-list">
        {group.children.map((c) =>
          c.kind === 'group' ? (
            <li key={c.id}>
              <GroupView group={c} depth={depth + 1} ctx={ctx} root={false} onAdd={onAdd} />
            </li>
          ) : c.kind === 'unsup' ? (
            <UnsupRow key={c.id} node={c} ctx={ctx} />
          ) : (
            <LeafRow key={c.id} leaf={c} ctx={ctx} />
          ),
        )}
      </ul>
      <div className="cond-add">
        <button type="button" className="btn secondary sm" disabled={ctx.locked} onClick={() => onAdd(group.id, emptyLeaf())}>
          <Icon d="M5 12h14M12 5v14" />
          加入條件
        </button>
        {depth < MAX_DEPTH && (
          <button type="button" className="btn secondary sm" disabled={ctx.locked} onClick={() => onAdd(group.id, emptyGroup())}>
            加入群組
          </button>
        )}
        {root && (
          <button type="button" className="btn ghost-dark sm" disabled={ctx.locked} onClick={ctx.clear}>
            清除篩選
          </button>
        )}
      </div>
    </div>
  )
}

/** 改動某個群組（含根） */
function updateGroup(g: FGroup, id: string, fn: (g: FGroup) => FGroup): FGroup {
  if (g.id === id) return fn(g)
  return { ...g, children: g.children.map((c) => (c.kind === 'group' ? updateGroup(c, id, fn) : c)) }
}

export interface Locate {
  field: string
  seq: number
}

export function EventFilterEditor({
  kind,
  event,
  unlimited,
  value,
  onCommit,
  issues,
  nodeIndex,
  locate = null,
}: {
  kind: 'source' | 'wait'
  event: string
  /** 等事件節點是否不設時限（影響摘要句尾） */
  unlimited: boolean
  /** 目前草稿裡的 event_filter */
  value: unknown
  /** 寫入草稿；null = 沒有篩選 */
  onCommit: (filter: unknown) => void
  issues: readonly Issue[]
  nodeIndex: number
  /** 驗證結果按「定位節點」：依 field 捲到並聚焦對應條件列 */
  locate?: Locate | null
}) {
  const locked = useLocked()
  const curKey = wireKey(value)
  const [tree, setTree] = useState<FGroup>(() => treeFromWire(value))
  const [synced, setSynced] = useState(curKey)
  const [undo, setUndo] = useState<{ tree: FGroup; count: number } | null>(null)
  // 草稿被外部換掉（例如載入最新版）：以草稿為準重建
  if (curKey !== synced) {
    setSynced(curKey)
    setTree(treeFromWire(value))
    setUndo(null)
  }
  const keys = useEventKeys(event.trim())
  const wired = useMemo(() => toWire(tree), [tree])
  const issueList = useMemo(() => filterIssues(issues, nodeIndex), [issues, nodeIndex])
  const uid = useId()
  const boxRef = useRef<HTMLFieldSetElement>(null)
  const wiredPaths = wired.paths
  useEffect(() => {
    if (!locate) return
    const m = new RegExp(String.raw`^definition\.nodes\[${nodeIndex}\]\.config\.(event_filter(?:\.children\[\d+\])*)`).exec(locate.field)
    if (!m) return
    const id = [...wiredPaths].find(([, p]) => p === m[1])?.[0]
    const el = id ? boxRef.current?.querySelector<HTMLElement>(`[data-fid="${id}"]`) : boxRef.current
    if (!el) return
    el.scrollIntoView?.({ block: 'center' })
    el.querySelector<HTMLElement>('[aria-invalid="true"], select, input, button')?.focus()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locate?.seq])

  const commit = (next: FGroup, snap: { tree: FGroup; count: number } | null = null) => {
    if (locked) return
    const { filter } = toWire(next)
    setTree(next)
    setSynced(wireKey(filter))
    setUndo(snap)
    if (wireKey(filter) !== curKey) onCommit(filter)
  }
  const update = (id: string, fn: (n: FNode) => FNode | null) => commit(id === tree.id ? (fn(tree) as FGroup) : mapTree(tree, id, fn))
  const add = (gid: string, n: FNode) => commit(updateGroup(tree, gid, (g) => ({ ...g, children: [...g.children, n] })))
  const remove = (id: string) => {
    const next = mapTree(tree, id, () => null)
    if (countLeaves(next) === 0) commit({ ...tree, children: [] }, { tree, count: countLeaves(tree) })
    else commit(next)
  }
  const clear = () => commit({ ...tree, children: [] }, { tree, count: countLeaves(tree) })

  const ctx: Ctx = { keys, issues: issueList, paths: wired.paths, locked, update, remove, clear }
  const empty = tree.children.length === 0
  const sum = blockSummary({ kind, event: event.trim(), unlimited, filter: wired.filter, incomplete: countIncomplete(tree) })
  const blockErrs = issuesAt(issueList, 'event_filter')
  const legend = kind === 'source' ? '只讓符合條件的事件進入（選填）' : '只算符合條件的事件（選填）'

  return (
    <fieldset className="field ef" ref={boxRef}>
      <legend>{legend}</legend>
      <p className={`fsum${sum.tone === 'todo' ? ' todo' : ''}`} role="status" aria-live="polite">
        {sum.text}
      </p>
      <Err id={`${uid}-be`} messages={[...blockErrs.row, ...blockErrs.field, ...blockErrs.operator, ...blockErrs.value]} />
      {keys.status === 'failed' && (
        <p className="ef-warn">
          <span>欄位建議暫時載不到，仍可手動輸入欄位代碼。</span>
          <button type="button" className="btn secondary sm" onClick={keys.retry}>
            重試
          </button>
        </p>
      )}
      {undo && (
        <div className="undo" role="status">
          <span>已清除 {undo.count} 項條件</span>
          <button
            type="button"
            className="btn sm"
            disabled={locked}
            onClick={() => {
              commit(undo.tree)
            }}
          >
            復原
          </button>
        </div>
      )}
      {empty ? (
        <div className="efempty">
          <h4>目前沒有篩選</h4>
          <p>{kind === 'source' ? '沒有篩選時，每一筆這個事件都會讓客戶進入旅程。' : '沒有篩選時，任何一筆這個事件都算等到。'}</p>
          <button type="button" className="btn secondary sm" disabled={locked} onClick={() => add(tree.id, emptyLeaf())}>
            加入第一個條件
          </button>
        </div>
      ) : (
        <GroupView group={tree} depth={1} ctx={ctx} root onAdd={add} />
      )}
      <span className="hint">只能比對事件本身的屬性（不是客戶資料）。最多 5 層；沒填完的條件不會寫入草稿。</span>
    </fieldset>
  )
}
