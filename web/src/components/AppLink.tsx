import type { AnchorHTMLAttributes, MouseEvent } from 'react'
import { navigate } from '../router'

/** 站內連結：保留原生 <a href>（可聚焦、可開新分頁），一般點擊改走 History API */
export function AppLink({ to, onClick, ...rest }: { to: string } & AnchorHTMLAttributes<HTMLAnchorElement>) {
  function handle(e: MouseEvent<HTMLAnchorElement>) {
    onClick?.(e)
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    e.preventDefault()
    navigate(to)
  }
  return <a {...rest} href={to} onClick={handle} />
}
