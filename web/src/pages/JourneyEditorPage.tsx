// 旅程編輯畫布頁（FE-04）：載入／空／錯誤／權限狀態，頁首、工具列、畫布、狀態列、右側面板。
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import type { Pt } from '../graph/route'
import { AppLink } from '../components/AppLink'
import { Icon } from '../components/CanvasIcons'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox } from '../components/ui'
import { navigate, setNavigationBlocker } from '../router'
import { useAuth } from '../auth'
import { BannerBar, friendlyTime } from '../canvas/Banners'
import { Canvas } from '../canvas/Canvas'
import {
  findLoops,
  hasEventSource,
  displayName,
  makeNode,
  nodeById,
  connect,
  freePortCount,
  freePorts,
  nudgeFree,
  placeNew,
  removeNode,
  setOutput,
  textOutline,
  updateNode,
  fillLayout,
  type Def,
} from '../canvas/model'
import { NodeLibrary } from '../canvas/NodeLibrary'
import { LockProvider } from '../canvas/lock'
import { NodePanel } from '../canvas/NodePanel'
import { SettingsPanel } from '../canvas/SettingsPanel'
import { useDraftEditor } from '../canvas/useDraftEditor'
import { ValidationPanel } from '../canvas/ValidationPanel'
import { Dialog } from './review/Dialog'
import { SubmitDialog, WithdrawDialog } from './review/Dialogs'
import { withdrawBlock } from './review/model'

function NoAccess({ id }: { id: string }) {
  return (
    <div className="card msg" role="alert">
      <h1 className="h1-card">你的角色（檢視者）不能編輯旅程</h1>
      <p>可以查看旅程與各節點人數；要修改請聯絡編輯者或管理員。</p>
      <p>
        <AppLink className="btn secondary" to={`/journeys/${encodeURIComponent(id)}`}>
          回到旅程頁
        </AppLink>
      </p>
    </div>
  )
}

const pad2 = (n: number) => String(n).padStart(2, '0')
/** 已儲存（HH:mm）用的本地時間 */
const hhmm = (iso: string | null | undefined) => {
  const d = iso ? new Date(iso) : null
  return d && !Number.isNaN(d.getTime()) ? `${pad2(d.getHours())}:${pad2(d.getMinutes())}` : ''
}

type Leave = { to: string; phase: 'ask' | 'saving' | 'failed' }

/** 有未儲存變更時離開編輯頁的提醒（三鈕）；儲存失敗改顯示錯誤與「重試儲存」 */
function LeaveDialog({
  leave,
  reason,
  onStay,
  onDiscard,
  onSave,
}: {
  leave: Leave
  reason: string
  onStay: () => void
  onDiscard: () => void
  onSave: () => void
}) {
  const busy = leave.phase === 'saving'
  if (leave.phase === 'failed') {
    return (
      <Dialog key="failed" title="儲存失敗，還不能離開" onClose={onStay}>
        <p className="msg error dlg-msg" role="alert">
          {reason}
        </p>
        <div className="dlg-actions lv-actions">
          <button type="button" className="btn danger secondary" onClick={onDiscard}>
            不儲存，直接離開
          </button>
          <button type="button" className="btn" data-autofocus="" onClick={onSave}>
            重試儲存
          </button>
        </div>
      </Dialog>
    )
  }
  return (
    <Dialog key="ask" title="要把修改存成草稿嗎？" onClose={() => !busy && onStay()}>
      <p>你有修改還沒儲存。現在離開，這些修改會不見。</p>
      <div className="dlg-actions lv-actions">
        <button type="button" className="btn secondary" data-autofocus="" aria-disabled={busy} onClick={() => !busy && onStay()}>
          繼續編輯
        </button>
        <button type="button" className="btn danger secondary" aria-disabled={busy} onClick={() => !busy && onDiscard()}>
          不儲存，直接離開
        </button>
        <button type="button" className="btn" aria-disabled={busy} onClick={() => !busy && onSave()}>
          {busy ? '儲存中…' : '儲存並離開'}
        </button>
      </div>
    </Dialog>
  )
}

function Editor({ id }: { id: string }) {
  const ed = useDraftEditor(id)
  const [selected, setSelected] = useState<string | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  // 收合的面板以「哪個面板」記錄：換選別的節點或面板就自動展開
  const [collapsedKey, setCollapsedKey] = useState<string | null>(null)
  const [locate, setLocate] = useState<{ field: string; seq: number } | null>(null)
  const [selectedEdge, setSelectedEdge] = useState<string | null>(null)
  const [focusPort, setFocusPort] = useState<{ port: string; seq: number } | null>(null)
  const [live, setLive] = useState('')
  const flowId = useId()
  const { def, layout } = ed
  const [dialog, setDialog] = useState<'submit' | 'withdraw' | null>(null)
  const { user } = useAuth()
  const pending = ed.review?.status === 'PENDING'
  const locked = pending || ed.banner?.kind === 'inReview'
  const [leave, setLeave] = useState<Leave | null>(null)

  // ---------- 離開提醒：站內導覽（返回、麵包屑、側欄）由 router 的攔截器轉成對話框；關分頁／重整用 beforeunload ----------
  const unsaved = ed.dirty || ed.saving
  const guard = useRef({ unsaved: false, saving: false, bypass: false, phase: null as Leave['phase'] | null, mounted: true, start: null as ((to: string) => void) | null })
  useEffect(() => {
    guard.current.unsaved = unsaved
    guard.current.saving = ed.saving
    guard.current.phase = leave?.phase ?? null
  }, [unsaved, ed.saving, leave])
  useEffect(() => {
    const g = guard.current
    g.mounted = true
    setNavigationBlocker((to) => {
      if (g.bypass || !g.unsaved) return false
      // 離開對話框已在儲存中：忽略重複點擊；一般儲存進行中點連結：等這次儲存完成再依結果導頁或顯示失敗對話框
      if (g.phase === 'saving') return true
      if (g.saving) g.start?.(to)
      else setLeave({ to, phase: 'ask' })
      return true
    })
    return () => {
      g.mounted = false
      setNavigationBlocker(null)
    }
  }, [])
  useEffect(() => {
    if (!ed.dirty) return
    const onUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', onUnload)
    return () => window.removeEventListener('beforeunload', onUnload)
  }, [ed.dirty])
  const leaveNow = (to: string) => {
    guard.current.bypass = true
    navigate(to)
  }
  /** 存完（含等進行中的那次）再導頁；失敗改顯示失敗對話框，內容保留 */
  const runLeave = async (to: string) => {
    setLeave({ to, phase: 'saving' })
    const ok = await ed.flush()
    if (!guard.current.mounted) return
    if (ok) leaveNow(to)
    else setLeave({ to, phase: 'failed' })
  }
  useEffect(() => {
    guard.current.start = (to) => void runLeave(to)
  })
  const saveAndLeave = () => (leave ? runLeave(leave.to) : Promise.resolve())

  // Ctrl／Cmd＋S：儲存草稿（擋掉瀏覽器的另存網頁）
  const { save } = ed
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || e.key.toLowerCase() !== 's') return
      e.preventDefault()
      if (!leave) void save()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [save, leave])

  const errorIds = useMemo(() => new Set((ed.report?.errors ?? []).map((i) => i.node_id).filter((x): x is string => !!x)), [ed.report])
  const loops = useMemo(() => (def ? findLoops(def) : []), [def])

  // Esc：關閉面板並取消選取（對話框開著時由對話框自己處理）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.defaultPrevented || dialog || leave) return
      if ((e.target as HTMLElement | null)?.closest?.('[role="dialog"], [role="alertdialog"]')) return
      if (selectedEdge) setSelectedEdge(null)
      else if (selected) setSelected(null)
      else if (settingsOpen) setSettingsOpen(false)
      else if (ed.reportOpen) ed.setReportOpen(false)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [dialog, leave, selected, selectedEdge, settingsOpen, ed])

  const select = useCallback((nid: string | null) => {
    setSelected(nid)
    setSelectedEdge(null)
    setCollapsedKey(null)
    if (nid) setSettingsOpen(false)
  }, [])
  const openSettings = useCallback(() => {
    setSelected(null)
    setCollapsedKey(null)
    setSettingsOpen(true)
  }, [])

  const { edit } = ed
  const removeLink = useCallback(
    (edgeId: string) => {
      if (locked) return
      const at = edgeId.indexOf(':')
      const source = edgeId.slice(0, at)
      const port = edgeId.slice(at + 1)
      edit((d, l) => [updateNode(d, source, (n) => setOutput(n, port, '')), l])
      setSelectedEdge(null)
      setLive('已移除連線')
    },
    [edit, locked],
  )
  const deleteNode = useCallback(
    (nid: string) => {
      if (locked) return
      edit((d, l) => {
        const nodes = { ...l.nodes }
        delete nodes[nid]
        return [removeNode(d, nid), { nodes }]
      })
      setSelected(null)
      setLive('已刪除節點')
    },
    [edit, locked],
  )

  // Delete：選取連線就移除連線，否則刪除選取的節點（進入條件與開始不可刪）；在輸入欄位、對話框裡不處理
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Delete' || e.defaultPrevented || dialog || leave || locked) return
      const t = e.target as HTMLElement | null
      if (t?.closest?.('input, textarea, select, [contenteditable="true"], [role="dialog"], [role="alertdialog"]')) return
      if (selectedEdge) removeLink(selectedEdge)
      else {
        const n = def ? nodeById(def, selected) : undefined
        if (n && n.type !== 'StartNode' && n.type !== 'EventSourceNode') deleteNode(n.id)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [dialog, leave, locked, selectedEdge, selected, def, removeLink, deleteNode])

  const connectPorts = useCallback(
    (source: string, port: string, target: string) => {
      if (locked || !def || !connect(def, source, port, target)) return
      edit((d, l) => [connect(d, source, port, target) ?? d, l])
      setLive('已連接')
    },
    [def, edit, locked],
  )
  const portKey = useCallback(
    (nid: string, port: string) => {
      select(nid)
      setFocusPort((f) => ({ port, seq: (f?.seq ?? 0) + 1 }))
    },
    [select],
  )

  if (ed.phase === 'loading') {
    return (
      <>
        <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '編輯草稿' }]} />
        <div className="card" role="status" aria-label="載入草稿中" aria-busy="true">
          <span className="skel" style={{ width: '40%' }} />
          <span className="skel" />
          <span className="skel" style={{ width: '70%' }} />
        </div>
      </>
    )
  }
  if (ed.phase === 'error' || !def) {
    const err = ed.loadError
    if (err && (err.status === 403 || (err.status === 404 && err.code === 'DRAFT_NOT_FOUND') || (err.status === 404 && err.code === 'JOURNEY_NOT_FOUND'))) {
      if (err.status === 403) return <NoAccess id={id} />
      return (
        <>
          <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '編輯草稿' }]} />
          <div className="card msg">
            <h1 className="h1-card">{err.code === 'DRAFT_NOT_FOUND' ? '這個旅程目前沒有草稿。' : '找不到這個旅程。'}</h1>
            <p>{err.code === 'DRAFT_NOT_FOUND' ? '草稿可能已被送審核准，或已被捨棄。' : '它可能不存在，或網址有誤。'}</p>
            <p>
              <AppLink className="btn secondary" to={err.code === 'DRAFT_NOT_FOUND' ? `/journeys/${encodeURIComponent(id)}` : '/journeys'}>
                {err.code === 'DRAFT_NOT_FOUND' ? '看目前上線的版本' : '回旅程列表'}
              </AppLink>
            </p>
          </div>
        </>
      )
    }
    return (
      <>
        <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '編輯草稿' }]} />
        {err && <ErrorBox title="暫時無法載入草稿" error={err} onRetry={ed.reload} />}
      </>
    )
  }

  const setDef = (fn: (d: Def) => Def) => edit((d, l) => [fn(d), l])
  const sel = nodeById(def, selected)

  /** 新增節點：at 有值（拖入、空白處選單）放在指定位置；沒有就放在選取節點右側。
   *  from 指定要接的出口；沒指定時，已選取節點有未連的必填出口就自動接在它後面 */
  function addNode(type: string, at?: Pt, from?: { id: string; port: string }) {
    const created = makeNode(def!, type)
    const anchor = selected ? nodeById(def!, selected) : undefined
    const link = from ?? (!at && anchor && freePorts(anchor)[0] ? { id: anchor.id, port: freePorts(anchor)[0].port } : undefined)
    edit(
      (d, l) => {
        const ids = d.nodes.map((n) => n.id)
        const pos = at ? nudgeFree(l, ids, at) : placeNew(l, selected && nodeById(d, selected) ? selected : null, ids)
        const nodes = [...d.nodes, created].map((n) => (link && n.id === link.id ? setOutput(n, link.port, created.id) : n))
        return [{ ...d, nodes }, { nodes: { ...l.nodes, [created.id]: pos } }]
      },
      { keepLayout: !!at },
    )
    setSelected(created.id)
    setSelectedEdge(null)
    setSettingsOpen(false)
  }

  function setEntryEvent() {
    const created = makeNode(def!, 'EventSourceNode')
    edit((d, l) => {
      const start = d.nodes.find((n) => n.id === d.entry_node) ?? d.nodes[0]
      const nodes = [...d.nodes.map((n) => (start && n.id === start.id ? setOutput(n, 'next', created.id) : n)), created]
      return [{ ...d, nodes }, { nodes: { ...l.nodes, [created.id]: placeNew(l, start?.id ?? null, d.nodes.map((n) => n.id)) } }]
    })
    setSelected(created.id)
    setSettingsOpen(false)
  }

  const published = ed.detail?.latest_version ?? null
  const crumbs = [
    { label: '旅程', to: '/journeys' },
    { label: def.name || '未命名旅程', to: `/journeys/${encodeURIComponent(id)}` },
    { label: '編輯草稿' },
  ]
  const onlyStart = !hasEventSource(def)
  const saveState = ed.saving ? 'busy' : ed.saveError ? 'err' : ed.dirty ? 'dirty' : 'ok'
  const saveText =
    saveState === 'busy'
      ? '儲存中…'
      : saveState === 'err'
        ? `儲存失敗：${ed.saveError?.reason ?? ''}`
        : saveState === 'dirty'
          ? '有未儲存的變更'
          : `已儲存（${hhmm(ed.savedAt ?? ed.meta?.updatedAt)}）`
  const saveOff = !ed.dirty || ed.saving || locked
  const reportShown = ed.reportOpen && !!ed.report
  const panelKey = sel ? `node:${sel.id}` : settingsOpen ? 'settings' : reportShown ? 'report' : null
  const panelLabel = sel ? displayName(sel) : settingsOpen ? '旅程設定' : '驗證結果'
  const collapsed = panelKey !== null && collapsedKey === panelKey
  const errCount = ed.report?.errors.length ?? 0
  const outline = textOutline(def)
  const freeCount = onlyStart ? 0 : freePortCount(def)

  const rejected = !pending && ed.review?.status === 'REJECTED' ? ed.review : null
  const withdrawOff = pending ? withdrawBlock(ed.review, user) : undefined

  return (
    <div className="editor">
      <div className="edhead">
        <div className="ed-top">
          <Crumbs items={crumbs} />
          <ol className="vflow" aria-label="版本流程">
            {['草稿', pending ? '審核中' : rejected ? '已退回' : '審核中', '上線'].map((s, i) => (
              <li key={s} aria-current={i === (pending ? 1 : 0) ? 'step' : undefined} className={i === (pending ? 1 : 0) ? 'now' : ''}>
                <span className="vn">{i + 1}</span>
                <span>{s}</span>
              </li>
            ))}
          </ol>
        </div>
        <div className="eh">
          <AppLink className="btn secondary icon back" to={`/journeys/${encodeURIComponent(id)}`} aria-label="返回旅程頁">
            <Icon name="back" />
          </AppLink>
          <div className="nm">
            <h1>
              {def.name || '未命名旅程'} {published !== null && <Badge tone="success">進行中 v{published}</Badge>}{' '}
              <Badge tone="neutral">草稿 v{(published ?? 0) + 1}</Badge>
            </h1>
            <p className="muted">
              {published !== null ? `草稿基於 v${published}` : '從未發佈・剛建立'}
              {ed.meta && !onlyStart && (
                <>
                  ・最後儲存 {friendlyTime(ed.meta.updatedAt)}
                  {ed.meta.updatedBy ? `・${ed.meta.updatedBy.display_name}` : ''}
                </>
              )}
              {onlyStart && <>・先設定進入事件才能送審</>}
              {freeCount > 0 && <span className="status-warn">・還有 {freeCount} 個出口沒接下一步</span>}
            </p>
          </div>
          <div className="acts2">
            <span className={`sv ${saveState}`} role="status">
              {saveState === 'ok' && <Icon name="check" size={16} />}
              {saveState === 'err' && <Icon name="x" size={16} />}
              {saveState === 'dirty' && <span className="dot" aria-hidden="true" />}
              {saveState === 'busy' && <span className="spin" aria-hidden="true" />}
              {saveText}
            </span>
            <button type="button" className="btn secondary" onClick={openSettings}>
              旅程設定
            </button>
            <button type="button" className="btn secondary" aria-disabled={ed.validating} onClick={() => !ed.validating && void ed.validate()}>
              驗證
            </button>
            <button
              type="button"
              className={`btn secondary${saveState === 'dirty' || saveState === 'err' ? ' save-dirty' : ''}`}
              aria-disabled={saveOff}
              aria-describedby={locked && pending ? `${flowId}-lock` : undefined}
              aria-keyshortcuts="Control+S Meta+S"
              onClick={() => !saveOff && void ed.save()}
            >
              {saveState === 'err' ? '重試儲存' : '儲存草稿'}
              <kbd aria-hidden="true">Ctrl S</kbd>
            </button>
            {pending ? (
              <span className="act">
                <button
                  type="button"
                  className="btn secondary"
                  aria-disabled={withdrawOff ? true : undefined}
                  aria-describedby={withdrawOff ? `${flowId}-wd` : undefined}
                  onClick={() => !withdrawOff && setDialog('withdraw')}
                >
                  撤回送審
                </button>
                {withdrawOff && (
                  <span id={`${flowId}-wd`} className="act-reason">
                    {withdrawOff}
                  </span>
                )}
              </span>
            ) : (
              <button type="button" className="btn" aria-disabled={ed.saving} onClick={() => !ed.saving && setDialog('submit')}>
                送審
              </button>
            )}
          </div>
        </div>
      </div>

      {pending && (
        <div className="msg info banner" role="status">
          <div>
            <p>
              <strong>已送審，等待管理員審核</strong>
            </p>
            <p id={`${flowId}-lock`}>草稿已鎖定，不能修改。要改內容請先撤回送審。</p>
          </div>
        </div>
      )}
      {rejected && (
        <div className="msg warn banner" role="status">
          <p>
            <strong>{rejected.decided_by?.display_name ?? '管理員'}</strong>
            {rejected.decided_at ? ` ${friendlyTime(rejected.decided_at)} ` : ' '}退回
            {rejected.reason ? `「${rejected.reason}」` : ''}。草稿已解鎖，修改後可再送審。
          </p>
        </div>
      )}
      {ed.banner && (
        <BannerBar
          banner={ed.banner}
          journeyId={id}
          def={def}
          layout={layout}
          onLoadLatest={() => void ed.loadLatest()}
          onWithdraw={() => setDialog('withdraw')}
          onRetry={() => ed.setBanner(null)}
          onDismiss={() => ed.setBanner(null)}
          onShowReport={() => ed.setReportOpen(true)}
        />
      )}

      <div className="edbody">
        <div className="edmain">
          <div className="edrow">
            <NodeLibrary onAdd={(t) => addNode(t)} locked={locked} />
          <div className="canvas-area" id={flowId}>
            <Canvas
              def={def}
              layout={layout}
              selectedId={selected}
              selectedEdge={selectedEdge}
              errorIds={errorIds}
              panelOpen={!!panelKey && !collapsed}
              locked={locked}
              onSelect={select}
              onSelectEdge={(eid) => !locked && setSelectedEdge(eid)}
              onConnect={connectPorts}
              onRemoveLink={removeLink}
              onPortKey={portKey}
              onAddAt={addNode}
              onMove={(nid, pos) => edit((d, l) => [d, { nodes: { ...fillLayout(d, l).nodes, [nid]: pos } }])}
            />
            <LockProvider value={locked}>
              {panelKey && collapsed && (
                <button type="button" className="drtab" aria-label={`展開『${panelLabel}』面板`} onClick={() => setCollapsedKey(null)}>
                  <span aria-hidden="true">‹</span>
                  <span className="drtab-text">{panelLabel}</span>
                </button>
              )}
              <div className="edside" hidden={!panelKey || collapsed}>
          {sel && (
            <NodePanel
              key={sel.id}
              node={sel}
              def={def}
              keys={ed.fieldKeys}
              onChange={(fn) => setDef((d) => updateNode(d, sel.id, fn))}
              onConnect={(port, target) => {
                setDef((d) => updateNode(d, sel.id, (n) => setOutput(n, port, target)))
                if (!target) setLive('已移除連線')
              }}
              focusPort={focusPort}
              onDelete={() => deleteNode(sel.id)}
              onClose={() => setSelected(null)}
              onCollapse={() => setCollapsedKey(panelKey)}
              openSettings={openSettings}
              issues={ed.report?.errors ?? []}
              locate={locate}
            />
          )}
          {settingsOpen && <SettingsPanel def={def} onChange={setDef} onClose={() => setSettingsOpen(false)} onCollapse={() => setCollapsedKey(panelKey)} />}
          {ed.reportOpen && ed.report && (
            <ValidationPanel
              report={ed.report}
              busy={ed.validating}
              onRevalidate={() => void ed.validate()}
              onClose={() => ed.setReportOpen(false)}
              onCollapse={() => setCollapsedKey(panelKey)}
              onLocate={(nid, field) => {
                select(nid)
                setLocate({ field, seq: (locate?.seq ?? 0) + 1 })
              }}
              onSettings={openSettings}
            />
          )}
              </div>
            </LockProvider>
            {onlyStart && (
              <div className="empty-hint card">
                <p className="muted">還沒設定進入事件</p>
                <p>
                  <strong>因為什麼事件進入？</strong>
                </p>
                <p>
                  <button type="button" className="btn" onClick={setEntryEvent}>
                    設定進入事件
                  </button>
                </p>
                <p className="muted">接著加入等待、條件、訊息；按「驗證」沒問題就送審。</p>
              </div>
            )}
          </div>
          </div>

          <div className="statusbar" hidden={errCount === 0 && loops.length === 0}>
            {errCount > 0 && <span className="status-err">有 {errCount} 個錯誤</span>}
            {loops.map((lp) => (
              <span className="loop-note" role="status" key={`${lp.from}>${lp.to}`}>
                <strong>有迴圈：</strong>
                「{displayName(nodeById(def, lp.from)!)}」會回到「{displayName(nodeById(def, lp.to)!)}」，客戶可能重複經過，圈數沒有上限。{' '}
                <button type="button" className="btn secondary sm" onClick={() => select(lp.from)}>
                  定位
                </button>
              </span>
            ))}
          </div>

          <details className="outline">
            <summary>文字版節點清單（鍵盤與讀屏用）</summary>
            <ol>
              {outline.map((t, i) => (
                <li key={i}>{t}</li>
              ))}
            </ol>
          </details>
        </div>

      </div>
      <div className="sr-only" role="status" aria-live="polite">
        {live}
      </div>
      {leave && (
        <LeaveDialog
          leave={leave}
          reason={ed.saveError ? `${ed.saveError.reason}。` : ed.banner?.kind === 'conflict' ? '草稿已被其他人修改，請回到編輯頁處理。' : '草稿沒有存成功。'}
          onStay={() => setLeave(null)}
          onDiscard={() => leaveNow(leave.to)}
          onSave={() => void saveAndLeave()}
        />
      )}
      {dialog === 'submit' && (
        <SubmitDialog
          name={def.name || '未命名旅程'}
          publishedVersion={published}
          draftVersion={(published ?? 0) + 1}
          onConfirm={(note) => ed.submit(note)}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'withdraw' && (
        <WithdrawDialog
          run={(reason) => ed.withdraw(reason)}
          onClose={() => setDialog(null)}
          onDone={() => setDialog(null)}
          onReload={() => {
            setDialog(null)
            ed.reload()
          }}
        />
      )}
    </div>
  )
}

export function JourneyEditorPage({ id }: { id: string }) {
  const { user } = useAuth()
  if (user.role === 'VIEWER') return <NoAccess id={id} />
  return <Editor key={id} id={id} />
}
