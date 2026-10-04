import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as api from '../api/client'
import type { LoginInput, SignupInput, UserSummary } from '../api/types'
import { AuthContext, type AuthContextValue } from './authContext'

/**
 * ログイン状態を管理する。
 * - トークンは localStorage に保存し、アプリを開いたときに /api/auth/me で有効か確かめる
 * - ログアウトはトークンを捨てるだけ（バックエンドに API はない。docs/feature-specs/01_auth.md）
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null)
  // トークンがあるときだけ、確認が終わるまで「読み込み中」にする
  const [loading, setLoading] = useState(() => api.getToken() !== null)

  const logout = useCallback(() => {
    api.clearToken()
    setUser(null)
  }, [])

  useEffect(() => {
    // ログインが必要な API で 401 が返ったら（期限切れなど）ログアウトする
    api.setUnauthorizedHandler(logout)
    return () => api.setUnauthorizedHandler(null)
  }, [logout])

  useEffect(() => {
    if (!api.getToken()) {
      return
    }
    let cancelled = false
    api
      .fetchMe()
      .then((me) => {
        if (!cancelled) setUser(me)
      })
      .catch(() => {
        // 401 のときは unauthorizedHandler がトークンを捨てる。通信エラーのときは未ログインとして扱う
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (input: LoginInput) => {
    const res = await api.login(input)
    api.saveToken(res.token)
    setUser(res.user)
  }, [])

  const signup = useCallback(async (input: SignupInput) => {
    const res = await api.signup(input)
    api.saveToken(res.token) // 登録後はそのままログイン状態にする
    setUser(res.user)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ user, loading, login, signup, logout }),
    [user, loading, login, signup, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
