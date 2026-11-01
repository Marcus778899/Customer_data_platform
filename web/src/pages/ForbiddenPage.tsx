import { useEffect, useRef } from 'react'
import { ROLE_LABEL, useAuth } from '../auth'
import { AppLink } from '../components/AppLink'
import { Crumbs } from '../components/Shell'

/** 403 FORBIDDEN 整頁；外框與導覽照常可用 */
export function ForbiddenPage() {
  const { user } = useAuth()
  const h1 = useRef<HTMLHeadingElement>(null)
  useEffect(() => h1.current?.focus(), [])
  const viewer = user.role === 'VIEWER'
  return (
    <>
      <Crumbs items={[{ label: '旅程', to: '/journeys' }, { label: '無權限' }]} />
      <div className="card msg" role="alert">
        <h1 className="h1-card" tabIndex={-1} ref={h1}>
          你沒有權限使用這個功能
        </h1>
        <p>
          目前帳號「{user.display_name}」的角色是{ROLE_LABEL[user.role]}
          {viewer ? '，只能檢視資料。' : '，不能使用這個功能。'}
        </p>
        <p className="muted">{viewer ? '如需編輯權限，請聯絡管理員。' : '如需調整權限，請聯絡管理員。'}</p>
        <p>
          <AppLink className="btn secondary" to="/journeys">
            回旅程列表
          </AppLink>
        </p>
      </div>
    </>
  )
}
