import { createContext, useContext } from 'react'

export interface AuthInfo {
  username: string
  logout: () => void
}

export const AuthContext = createContext<AuthInfo | null>(null)

export function useAuth(): AuthInfo {
  const v = useContext(AuthContext)
  if (!v) throw new Error('AuthContext 不存在')
  return v
}
