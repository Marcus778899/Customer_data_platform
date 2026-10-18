import { Fragment } from 'react'
import { childPath, indexPath, isPlainObject } from '../format'
import { Badge } from './ui'

/** 是否「已遮罩」只看契約的 masked_paths（§4.4），不比對 *** 字樣（D-014／D-021） */
function Value({ value, path, masked }: { value: unknown; path: string; masked: ReadonlySet<string> }) {
  const badge = masked.has(path) ? <Badge tone="neutral">已遮罩</Badge> : null
  if (Array.isArray(value)) {
    return (
      <>
        <dl className="kv">
          {value.map((v, i) => (
            <Fragment key={i}>
              <dt>[{i}]</dt>
              <dd>
                <Value value={v} path={indexPath(path, i)} masked={masked} />
              </dd>
            </Fragment>
          ))}
        </dl>
        {badge}
      </>
    )
  }
  if (isPlainObject(value)) {
    return (
      <>
        <KeyValues data={value} basePath={path} masked={masked} />
        {badge}
      </>
    )
  }
  return (
    <>
      <span className="mono">{value === null ? 'null' : String(value)}</span> {badge}
    </>
  )
}

export function KeyValues({
  data,
  basePath,
  masked,
}: {
  data: Record<string, unknown>
  basePath: string
  masked: ReadonlySet<string>
}) {
  return (
    <dl className="kv">
      {Object.entries(data).map(([k, v]) => (
        <Fragment key={k}>
          <dt>{k}</dt>
          <dd>
            <Value value={v} path={childPath(basePath, k)} masked={masked} />
          </dd>
        </Fragment>
      ))}
    </dl>
  )
}
