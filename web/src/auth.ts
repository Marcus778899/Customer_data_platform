import { createContext, useContext } from 'react'
import type { CurrentUser, Role } from './api/types'

export interface AuthInfo {
  user: CurrentUser
  logout: () => void
}

export const ROLE_LABEL: Record<Role, string> = { VIEWER: '檢視者', EDITOR: '編輯者', ADMIN: '管理員' }

export const AuthContext = createContext<AuthInfo | null>(null)

export function useAuth(): AuthInfo {
  const v = useContext(AuthContext)
  if (!v) throw new Error('AuthContext 不存在')
  return v
}
