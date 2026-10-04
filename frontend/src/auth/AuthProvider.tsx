import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as api from '../api/client'
import type { LoginInput, SignupInput, UserSummary } from '../api/types'
import { AuthContext, type AuthContextValue } from './authContext'

/**
 * ログイン状態を管理する（docs/feature-specs/01_auth.md）。
 * - アプリを開いたら、リフレッシュトークン（HttpOnly Cookie）でアクセストークンを再発行して、ログイン状態を復元する
 * - ログアウトは、サーバーでリフレッシュトークンを無効にしてから、手元の状態を消す
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null)
  // Cookie は JavaScript から見えないので、ログイン状態かどうかは再発行を試すまでわからない
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    // 再発行もできずに 401 になったら（リフレッシュトークンの期限切れなど）ログアウト状態にする
    api.setUnauthorizedHandler(() => setUser(null))
    return () => api.setUnauthorizedHandler(null)
  }, [])

  useEffect(() => {
    let cancelled = false
    api
      .refresh()
      .then((res) => {
        if (!cancelled) setUser(res.user)
      })
      .catch(() => {
        // リフレッシュトークンがない・無効なら未ログイン。通信エラーのときも未ログインとして扱う
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
    setUser(res.user)
  }, [])

  const signup = useCallback(async (input: SignupInput) => {
    const res = await api.signup(input) // 登録後はそのままログイン状態にする
    setUser(res.user)
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.logout()
    } catch {
      // 通信に失敗しても、画面上はログアウトする（リフレッシュトークンは期限で無効になる）
    }
    setUser(null)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ user, loading, login, signup, logout }),
    [user, loading, login, signup, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
