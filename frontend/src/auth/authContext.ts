import { createContext, useContext } from 'react'
import type { LoginInput, SignupInput, UserSummary } from '../api/types'

export interface AuthContextValue {
  /** ログイン中のユーザー。未ログインなら null */
  user: UserSummary | null
  /** 起動時にログイン状態を確認（リフレッシュトークンで再発行）している間は true */
  loading: boolean
  login: (input: LoginInput) => Promise<void>
  signup: (input: SignupInput) => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth は AuthProvider の中で使ってください')
  }
  return value
}
