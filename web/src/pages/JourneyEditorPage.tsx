// 旅程編輯畫布頁（FE-04）：載入／空／錯誤／權限狀態，頁首、工具列、畫布、狀態列、右側面板。
import { useCallback, useId, useMemo, useState } from 'react'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'
import { Badge, ErrorBox } from '../components/ui'
import { useAuth } from '../auth'
import { BannerBar, friendlyTime } from '../canvas/Banners'
import { Canvas } from '../canvas/Canvas'
import {
  findLoops,
  hasEventSource,
  displayName,
  makeNode,
  nodeById,
  PALETTE,
  placeNew,
  removeNode,
  setOutput,
  textOutline,
  typeLabel,
  updateNode,
  fillLayout,
  type Def,
} from '../canvas/model'
import { NodePanel } from '../canvas/NodePanel'
import { SettingsPanel } from '../canvas/SettingsPanel'
import { useDraftEditor } from '../canvas/useDraftEditor'
import { ValidationPanel } from '../canvas/ValidationPanel'
import { navigate } from '../router'

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

function AddButtons({ onAdd }: { onAdd: (type: string) => void }) {
  return (
    <>
      {PALETTE.map((g) => (
        <span className="tb-group" role="group" aria-label={g.group} key={g.group}>
          {g.types.map((t) => (
            <button key={t} type="button" className={`btn secondary sm tb-btn cat-${t}`} onClick={() => onAdd(t)}>
              {typeLabel(t)}
            </button>
          ))}
        </span>
      ))}
    </>
  )
}

function Editor({ id }: { id: string }) {
  const ed = useDraftEditor(id)
  const [selected, setSelected] = useState<string | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const flowId = useId()
  const { def, layout } = ed

  const errorIds = useMemo(() => new Set((ed.report?.errors ?? []).map((i) => i.node_id).filter((x): x is string => !!x)), [ed.report])
  const loops = useMemo(() => (def ? findLoops(def) : []), [def])

  const select = useCallback((nid: string | null) => {
    setSelected(nid)
    if (nid) setSettingsOpen(false)
  }, [])
  const openSettings = useCallback(() => {
    setSelected(null)
    setSettingsOpen(true)
  }, [])

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

  const edit = ed.edit
  const setDef = (fn: (d: Def) => Def) => edit((d, l) => [fn(d), l])
  const sel = nodeById(def, selected)

  function addNode(type: string) {
    const created = makeNode(def!, type)
    edit((d, l) => {
      const pos = placeNew(l, selected && nodeById(d, selected) ? selected : null, d.nodes.map((n) => n.id))
      return [{ ...d, nodes: [...d.nodes, created] }, { nodes: { ...l.nodes, [created.id]: pos } }]
    })
    setSelected(created.id)
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
  const saveText = ed.saving
    ? '儲存中…'
    : ed.dirty || (!ed.savedAt && def.nodes.length <= 1)
      ? '尚未儲存'
      : `已自動儲存 ${friendlyTime(ed.savedAt ?? ed.meta?.updatedAt)}`
  const errCount = ed.report?.errors.length ?? 0
  const loop = loops[0]
  const outline = textOutline(def)

  async function submit() {
    if (await ed.submit()) navigate(`/journeys/${encodeURIComponent(id)}`)
  }

  return (
    <div className="editor">
      <div className="edhead">
        <div className="ed-top">
          <Crumbs items={crumbs} />
          <ol className="vflow" aria-label="版本流程">
            {['草稿', '審核中', '上線'].map((s, i) => (
              <li key={s} aria-current={i === 0 ? 'step' : undefined} className={i === 0 ? 'now' : ''}>
                <span className="vn">{i + 1}</span>
                <span>{s}</span>
              </li>
            ))}
          </ol>
        </div>
        <div className="pagehead">
          <div>
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
            </p>
          </div>
          <div className="head-act">
            <button type="button" className="btn secondary" onClick={openSettings}>
              旅程設定
            </button>
            <button type="button" className="btn secondary" aria-disabled={ed.validating} onClick={() => !ed.validating && void ed.validate()}>
              驗證
            </button>
            <button type="button" className="btn" onClick={() => void submit()}>
              送審
            </button>
          </div>
        </div>
      </div>

      {ed.banner && (
        <BannerBar
          banner={ed.banner}
          journeyId={id}
          def={def}
          layout={layout}
          onLoadLatest={() => void ed.loadLatest()}
          onWithdraw={() => void ed.withdraw()}
          onRetry={ed.banner.kind === 'submitFailed' ? () => ed.setBanner(null) : ed.retrySave}
          onShowReport={() => ed.setReportOpen(true)}
        />
      )}

      <div className="edbody">
        <div className="edmain">
          <div className="tb-full" role="toolbar" aria-label="加入節點">
            <AddButtons onAdd={addNode} />
          </div>
          <details className="addmenu">
            <summary>＋ 新增節點</summary>
            <div className="addmenu-list">
              <AddButtons onAdd={addNode} />
            </div>
          </details>

          <div className="canvas-area" id={flowId}>
            <Canvas
              def={def}
              layout={layout}
              selectedId={selected}
              errorIds={errorIds}
              onSelect={select}
              onMove={(nid, pos) => edit((d, l) => [d, { nodes: { ...fillLayout(d, l).nodes, [nid]: pos } }])}
            />
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

          <div className="statusbar">
            <span role="status">{saveText}</span>
            {errCount > 0 && <span className="status-err">有 {errCount} 個錯誤</span>}
            {loop && (
              <span className="loop-note" role="status">
                <strong>有迴圈：</strong>
                「{displayName(nodeById(def, loop.from)!)}」會回到「{displayName(nodeById(def, loop.to)!)}」，客戶可能重複經過，圈數沒有上限。{' '}
                <button type="button" className="btn secondary sm" onClick={() => select(loop.from)}>
                  定位
                </button>
              </span>
            )}
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

        <div className="edside">
          {sel && (
            <NodePanel
              key={sel.id}
              node={sel}
              def={def}
              keys={ed.fieldKeys}
              onChange={(fn) => setDef((d) => updateNode(d, sel.id, fn))}
              onConnect={(port, target) => setDef((d) => updateNode(d, sel.id, (n) => setOutput(n, port, target)))}
              onDelete={() => {
                edit((d, l) => {
                  const nodes = { ...l.nodes }
                  delete nodes[sel.id]
                  return [removeNode(d, sel.id), { nodes }]
                })
                setSelected(null)
              }}
              onClose={() => setSelected(null)}
              openSettings={openSettings}
            />
          )}
          {settingsOpen && <SettingsPanel def={def} onChange={setDef} onClose={() => setSettingsOpen(false)} />}
          {ed.reportOpen && ed.report && (
            <ValidationPanel
              report={ed.report}
              busy={ed.validating}
              onRevalidate={() => void ed.validate()}
              onClose={() => ed.setReportOpen(false)}
              onLocate={(nid) => select(nid)}
              onSettings={openSettings}
            />
          )}
        </div>
      </div>
    </div>
  )
}

export function JourneyEditorPage({ id }: { id: string }) {
  const { user } = useAuth()
  if (user.role === 'VIEWER') return <NoAccess id={id} />
  return <Editor key={id} id={id} />
}
