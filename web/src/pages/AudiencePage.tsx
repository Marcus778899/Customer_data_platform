import { useEffect, useRef } from 'react'
import { Badge } from '../components/ui'
import { Crumbs } from '../components/Shell'

/** 受眾佔位（D-034）：v0.2.0 不發任何 API，v0.4.0 實作 */
export function AudiencePage() {
  const h1 = useRef<HTMLHeadingElement>(null)
  useEffect(() => h1.current?.focus(), [])
  return (
    <>
      <Crumbs items={[{ label: '受眾' }]} />
      <div className="card msg">
        <h1 className="h1-card" tabIndex={-1} ref={h1}>
          受眾 <Badge tone="neutral">規劃中</Badge>
        </h1>
        <p>受眾功能預計在 v0.4.0 提供，屆時可在這裡建立客戶分群。</p>
        <p className="muted">目前請由「旅程」與「客戶」使用平台。</p>
      </div>
    </>
  )
}
