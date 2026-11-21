// 條件面板底部「試試看」：只用使用者輸入的樣本判斷，不讀客戶、不呼叫 API（docs/ui/canvas-interaction.md §3）。
import { useId, useState } from 'react'
import { type CondGroup, type CondLeaf } from './model'
import { buildExamples, evalTree, fragment, hasBadNot, hasUnsupported, leavesOf, nameOf, parseSample, prune, sampleFields, type ExampleRow } from './tryEval'

const PATH_CHECK = 'M20 6 9 17l-5-5'
const PATH_X = 'M18 6 6 18M6 6l12 12'
const PATH_FLASK = 'M10 2v7.527a2 2 0 0 1-.211.896L4.72 20.55a1 1 0 0 0 .9 1.45h12.76a1 1 0 0 0 .9-1.45l-5.069-10.127A2 2 0 0 1 14 9.527V2M8.5 2h7M7 16h10'

function Ico({ d }: { d: string }) {
  return (
    <svg className="try-ico" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d={d} />
    </svg>
  )
}

function Rows({ rows }: { rows: ExampleRow[] }) {
  return (
    <dl>
      {rows.map((r) => (
        <div key={r.field} className="try-ex-row">
          <dt>{r.name}</dt>
          <dd className={r.bads.length ? 'fail' : 'ok'}>
            <Ico d={r.bads.length ? PATH_X : PATH_CHECK} />
            <span>{r.shown}</span>
            {r.notes.map((n) => (
              <span key={n} className="why">
                {n}
              </span>
            ))}
            {r.bads.map((n) => (
              <span key={n} className="why">
                {n}
              </span>
            ))}
          </dd>
        </div>
      ))}
    </dl>
  )
}

export function TryIt({ cond }: { cond: CondGroup }) {
  const uid = useId()
  const [samples, setSamples] = useState<Record<string, string>>({})
  const unsupported = hasUnsupported(cond)
  const root = prune(cond)
  const leaves = root ? leavesOf(root) : []
  const fields = sampleFields(leaves)
  const asNum = new Map(fields.map((f) => [f.field, f.asNumber]))
  const actualOf = (l: CondLeaf) => parseSample(samples[l.field] ?? '', asNum.get(l.field) ?? false)
  const examples = !unsupported && root ? buildExamples(root) : null
  const pass = !unsupported && root ? evalTree(root, actualOf) : false
  const results = leaves.map((l) => evalTree(l, actualOf))
  const shownSample = (l: CondLeaf) => ((samples[l.field] ?? '').trim() === '' ? '沒填' : samples[l.field])

  return (
    <details className="try-box" open>
      <summary>
        <Ico d={PATH_FLASK} />
        試試看：這個條件怎麼判斷
      </summary>
      <div className="try-body">
        {unsupported ? (
          <p className="hint">這個條件裡有試算不支援的項目（客戶行為、客群），無法在這裡試算。</p>
        ) : !root ? (
          <p className="hint">先加入條件並選好欄位，就能在這裡試算。</p>
        ) : null}
        {root && !unsupported && hasBadNot(root) && <p className="hint">「但不可以是…」要剛好一個條件，引擎才可能讓它成立；現在不會有客戶符合。</p>}
        {examples && root && (
          <div className="try-ex">
            <div className="yes">
              <p className="hd">
                <Ico d={PATH_CHECK} />
                一位會符合的客戶
              </p>
              {examples.yes ? <Rows rows={examples.yes} /> : <p className="why-none">這個條件沒有客戶能符合（條件互相矛盾）。</p>}
            </div>
            <div className="no">
              <p className="hd">
                <Ico d={PATH_X} />
                一位不會符合的客戶
              </p>
              {examples.no ? <Rows rows={examples.no} /> : <p className="why-none">每位客戶都會符合。</p>}
            </div>
          </div>
        )}
        {root && (
          <fieldset className="try-inputs" disabled={unsupported}>
            <legend>自己填填看：沒填代表這位客戶沒有這個欄位</legend>
            {fields.map((f, i) => (
              <label key={f.field} htmlFor={`${uid}-${i}`}>
                {f.name}
                <input
                  id={`${uid}-${i}`}
                  type="text"
                  value={samples[f.field] ?? ''}
                  placeholder="（沒填）"
                  inputMode={f.asNumber ? 'decimal' : undefined}
                  onChange={(e) => setSamples((s) => ({ ...s, [f.field]: e.target.value }))}
                />
              </label>
            ))}
          </fieldset>
        )}
        {root && !unsupported && (
          <div className={`try-verdict ${pass ? 'ok' : 'no'}`} role="status">
            <Ico d={pass ? PATH_CHECK : PATH_X} />
            <div>
              <p>{pass ? '這位客戶會符合：走「是」' : '這位客戶不會符合：走「否」'}</p>
              <ul>
                {leaves.map((l, i) => (
                  <li key={i} className={results[i] ? 'p' : 'f'}>
                    <Ico d={results[i] ? PATH_CHECK : PATH_X} />
                    <span>
                      『{nameOf(l.field)}』{fragment(l)} <small>（你填的：{shownSample(l)}）</small>
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}
        <p className="try-fine">只用你在這裡輸入的樣本判斷，不會讀取真實客戶，也不會改動旅程。</p>
      </div>
    </details>
  )
}
