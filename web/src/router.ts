// 極簡 History API 路由：/login、/、/enrollments/:id（不引入路由套件）
import { useSyncExternalStore } from 'react'

const EVENT = 'cdp:navigate'
let lastNav: 'push' | 'replace' | 'pop' = 'push'

export interface Loc {
  pathname: string
  search: string
}

/** 離開攔截（編輯頁有未儲存變更時用）：回傳 true = 這次站內導覽被攔下（由攔截者決定要不要再導）；replace 導覽（登入導向等）不攔 */
export type NavigationBlocker = (to: string) => boolean
let blocker: NavigationBlocker | null = null
export function setNavigationBlocker(fn: NavigationBlocker | null) {
  blocker = fn
}

export function navigate(to: string, opts: { replace?: boolean; state?: unknown } = {}) {
  if (!opts.replace && blocker?.(to)) return
  if (opts.replace) window.history.replaceState(opts.state ?? null, '', to)
  else window.history.pushState(opts.state ?? null, '', to)
  lastNav = opts.replace ? 'replace' : 'push'
  window.dispatchEvent(new Event(EVENT))
}

export const lastNavigationWasPop = () => lastNav === 'pop'

function subscribe(cb: () => void) {
  const onPop = () => {
    lastNav = 'pop'
    cb()
  }
  window.addEventListener('popstate', onPop)
  window.addEventListener(EVENT, cb)
  return () => {
    window.removeEventListener('popstate', onPop)
    window.removeEventListener(EVENT, cb)
  }
}

const snapshot = () => window.location.pathname + window.location.search

export function useLocation(): Loc {
  const s = useSyncExternalStore(subscribe, snapshot)
  const i = s.indexOf('?')
  return i < 0 ? { pathname: s, search: '' } : { pathname: s.slice(0, i), search: s.slice(i) }
}

/** 登入後要回的位置；只接受站內路徑 */
export function safeNext(search: string): string {
  const next = new URLSearchParams(search).get('next')
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/login')) return '/'
  return next
}
