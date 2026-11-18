// 產品外框（UI-03）：桌面左側欄；< 1024px 改頂列＋「選單」。沒有工作區切換（D-051）。
import { useState, type MouseEvent, type ReactNode } from 'react'
import { ROLE_LABEL, useAuth } from '../auth'
import { AppLink } from './AppLink'

export const PRODUCT_NAME = '客戶旅程平台'
export const MAIN_ID = 'main-content'

export type NavKey = 'journeys' | 'customers' | 'audience' | null

const NAV: { key: Exclude<NavKey, null>; to: string; label: string; soon?: boolean; icon: string }[] = [
  { key: 'journeys', to: '/journeys', label: '旅程', icon: 'M6 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM18 16a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM8 6h5a4 4 0 0 1 0 8h-2a4 4 0 0 0 0 8' },
  { key: 'customers', to: '/customers', label: '客戶', icon: 'M16 20v-1a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v1M10 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM20 20v-1a4 4 0 0 0-3-3.9' },
  { key: 'audience', to: '/audience', label: '受眾', soon: true, icon: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z' },
]

/** 編輯頁（畫布）側欄預設收成圖示軌，讓畫布多出空間 */
const EDITOR_PATH = /^\/journeys\/[^/?]+\/edit\/?(\?|$)/

function Nav({ label, current, rail }: { label: string; current: NavKey; rail?: boolean }) {
  return (
    <nav className="nav" aria-label={label}>
      <ul>
        {NAV.map((n) => (
          <li key={n.key}>
            <AppLink to={n.to} className={rail ? 'rl' : undefined} aria-label={rail ? n.label : undefined} aria-current={current === n.key ? 'page' : undefined}>
              {rail && (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d={n.icon} />
                </svg>
              )}
              <span className="nl-text">{n.label}</span>
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
  const [expanded, setExpanded] = useState(false)
  const rail = EDITOR_PATH.test(menuKey) && !expanded
  return (
    <div className={`shell${rail ? ' rail' : ''}`}>
      <a className="skip" href={`#${MAIN_ID}`} onClick={skip}>
        跳到主要內容
      </a>
      <aside className="side">
        <div className="brand">{PRODUCT_NAME}</div>
        <Nav label="主要導覽" current={current} rail={rail} />
        <Account />
        {EDITOR_PATH.test(menuKey) && (
          <button type="button" className="btn ghost sm side-toggle" aria-pressed={expanded} onClick={() => setExpanded((v) => !v)}>
            {expanded ? '縮成圖示側欄' : '顯示完整側欄'}
          </button>
        )}
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
        {items.map((c, i) => {
          const last = i === items.length - 1
          return last || !c.to ? (
            <li key={c.label} aria-current={last ? 'page' : undefined}>
              {c.label}
            </li>
          ) : (
            <li key={c.label}>
              <AppLink to={c.to}>{c.label}</AppLink>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
