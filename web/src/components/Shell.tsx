// 產品外框（UI-03）：桌面左側欄；< 1024px 改頂列＋「選單」。沒有工作區切換（D-051）。
import type { MouseEvent, ReactNode } from 'react'
import { ROLE_LABEL, useAuth } from '../auth'
import { AppLink } from './AppLink'

export const PRODUCT_NAME = '客戶旅程平台'
export const MAIN_ID = 'main-content'

export type NavKey = 'journeys' | 'customers' | 'audience' | null

const NAV: { key: Exclude<NavKey, null>; to: string; label: string; soon?: boolean }[] = [
  { key: 'journeys', to: '/', label: '旅程' },
  { key: 'customers', to: '/customers', label: '客戶' },
  { key: 'audience', to: '/audience', label: '受眾', soon: true },
]

function Nav({ label, current }: { label: string; current: NavKey }) {
  return (
    <nav className="nav" aria-label={label}>
      <ul>
        {NAV.map((n) => (
          <li key={n.key}>
            <AppLink to={n.to} aria-current={current === n.key ? 'page' : undefined}>
              {n.label}
              {n.soon && <span className="tag">規劃中</span>}
            </AppLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}

function Account() {
  const { user, logout } = useAuth()
  return (
    <div className="who-side">
      <span>
        已登入：<strong>{user.display_name}</strong>
      </span>
      <span className="role">角色：{ROLE_LABEL[user.role]}</span>
      <AppLink to="/account/password">修改密碼</AppLink>
      <button className="btn ghost sm" type="button" onClick={logout}>
        登出
      </button>
    </div>
  )
}

export function Shell({ current, menuKey, children }: { current: NavKey; menuKey: string; children: ReactNode }) {
  function skip(e: MouseEvent) {
    e.preventDefault()
    document.getElementById(MAIN_ID)?.focus()
  }
  return (
    <div className="shell">
      <a className="skip" href={`#${MAIN_ID}`} onClick={skip}>
        跳到主要內容
      </a>
      <aside className="side">
        <div className="brand">{PRODUCT_NAME}</div>
        <Nav label="主要導覽" current={current} />
        <Account />
      </aside>
      <div className="stage">
        <header className="top">
          <strong>{PRODUCT_NAME}</strong>
          {/* key 隨頁面變：換頁後選單自動收合 */}
          <details className="menu" key={menuKey}>
            <summary>選單</summary>
            <div className="drop">
              <Nav label="主要導覽（窄螢幕）" current={current} />
              <Account />
            </div>
          </details>
        </header>
        <main className="page" id={MAIN_ID} tabIndex={-1}>
          {children}
        </main>
      </div>
    </div>
  )
}

export function Crumbs({ items }: { items: { label: string; to?: string }[] }) {
  return (
    <nav className="crumbs" aria-label="麵包屑">
      <ol>
        {items.map((c) => (
          <li key={c.label}>{c.to ? <AppLink to={c.to}>{c.label}</AppLink> : c.label}</li>
        ))}
      </ol>
    </nav>
  )
}
