// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, test, vi } from 'vitest'
import { AuthContext, useAuth, type AuthContextValue } from './authContext'

describe('useAuth：ログイン状態を部品から使う', () => {
  test('AuthProvider（AuthContext）の中では、その値を返す', () => {
    const value: AuthContextValue = {
      user: { id: 1, username: 'alice', displayName: 'アリス', iconUrl: null },
      loading: false,
      login: vi.fn(),
      signup: vi.fn(),
      logout: vi.fn(),
      updateUser: vi.fn(),
    }
    const wrapper = ({ children }: { children: ReactNode }) => (
      <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
    )

    const { result } = renderHook(() => useAuth(), { wrapper })

    expect(result.current).toBe(value)
  })

  test('AuthProvider の外で使うと、分かりやすいエラーにする（使い方の間違いに早く気づくため）', () => {
    // React が出すエラーのログで、テストの出力が埋まらないようにする
    vi.spyOn(console, 'error').mockImplementation(() => {})

    expect(() => renderHook(() => useAuth())).toThrow('useAuth は AuthProvider の中で使ってください')
  })
})
