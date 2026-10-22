// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import * as api from '../api/client'
import { ApiError } from '../api/client'
import type { AuthResponse } from '../api/types'
import { aUser, deferred } from '../test/helpers'
import { AuthProvider } from './AuthProvider'
import { useAuth } from './authContext'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  refresh: vi.fn(),
  login: vi.fn(),
  signup: vi.fn(),
  logout: vi.fn(),
  setUnauthorizedHandler: vi.fn(),
}))

const alice = aUser({ id: 1, username: 'alice', displayName: 'アリス' })
const authResponse = (user = alice) => ({ accessToken: 'token', user }) as AuthResponse

beforeEach(() => {
  vi.mocked(api.refresh).mockReset()
  vi.mocked(api.login).mockReset()
  vi.mocked(api.signup).mockReset()
  vi.mocked(api.logout).mockReset()
  vi.mocked(api.setUnauthorizedHandler).mockReset()
})

/** ログイン状態を画面に出し、ボタンで login などを呼ぶ */
function Consumer() {
  const { user, loading, login, signup, logout, updateUser } = useAuth()
  return (
    <>
      <output data-testid="state">{loading ? '確認中' : (user?.username ?? '未ログイン')}</output>
      <button type="button" onClick={() => login({ email: 'a@example.com', password: 'password' }).catch(() => {})}>
        login
      </button>
      <button
        type="button"
        onClick={() =>
          signup({ username: 'carol', displayName: 'キャロル', email: 'c@example.com', password: 'password' }).catch(
            () => {},
          )
        }
      >
        signup
      </button>
      <button type="button" onClick={() => void logout()}>
        logout
      </button>
      <button type="button" onClick={() => updateUser({ ...alice, displayName: '新しい名前', username: 'alice2' })}>
        update
      </button>
    </>
  )
}

const state = () => screen.getByTestId('state')

function renderProvider() {
  return render(
    <AuthProvider>
      <Consumer />
    </AuthProvider>,
  )
}

describe('AuthProvider：起動時のログイン状態の復元（状態遷移）', () => {
  test('確認中 → リフレッシュトークンで再発行できたらログイン状態', async () => {
    const response = deferred<AuthResponse>()
    vi.mocked(api.refresh).mockReturnValue(response.promise)
    renderProvider()

    expect(state()).toHaveTextContent('確認中')
    response.resolve(authResponse())

    await vi.waitFor(() => expect(state()).toHaveTextContent('alice'))
  })

  test.each([
    ['リフレッシュトークンがない・無効', new ApiError(401, 'UNAUTHENTICATED', '文言')],
    ['通信エラー', new ApiError(0, 'NETWORK_ERROR', '文言')],
  ])('再発行できなければ（%s）未ログイン', async (_label, error) => {
    vi.mocked(api.refresh).mockRejectedValue(error)
    renderProvider()

    await vi.waitFor(() => expect(state()).toHaveTextContent('未ログイン'))
  })

  test('確認の途中で画面が閉じられたら、あとで結果が届いても何もしない', async () => {
    const response = deferred<AuthResponse>()
    vi.mocked(api.refresh).mockReturnValue(response.promise)
    const consoleError = vi.spyOn(console, 'error')
    const { unmount } = renderProvider()

    unmount()
    await act(async () => response.resolve(authResponse()))

    expect(consoleError).not.toHaveBeenCalled()
    consoleError.mockRestore()
  })
})

describe('AuthProvider：ログイン・新規登録・ログアウト', () => {
  beforeEach(() => {
    vi.mocked(api.refresh).mockRejectedValue(new ApiError(401, 'UNAUTHENTICATED', '文言'))
  })

  async function ready() {
    renderProvider()
    await vi.waitFor(() => expect(state()).toHaveTextContent('未ログイン'))
  }

  test('ログインできたら、そのユーザーでログイン状態にする', async () => {
    vi.mocked(api.login).mockResolvedValue(authResponse())
    await ready()

    await userEvent.click(screen.getByRole('button', { name: 'login' }))

    await vi.waitFor(() => expect(state()).toHaveTextContent('alice'))
    expect(api.login).toHaveBeenCalledWith({ email: 'a@example.com', password: 'password' })
  })

  test('ログインに失敗したら、未ログインのまま（エラーは呼び出し元へ渡す）', async () => {
    vi.mocked(api.login).mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', '文言'))
    await ready()

    await userEvent.click(screen.getByRole('button', { name: 'login' }))

    await vi.waitFor(() => expect(api.login).toHaveBeenCalled())
    expect(state()).toHaveTextContent('未ログイン')
  })

  test('新規登録できたら、そのままログイン状態にする', async () => {
    vi.mocked(api.signup).mockResolvedValue(authResponse(aUser({ username: 'carol' })))
    await ready()

    await userEvent.click(screen.getByRole('button', { name: 'signup' }))

    await vi.waitFor(() => expect(state()).toHaveTextContent('carol'))
  })

  test.each([
    ['ログアウトできた', () => vi.mocked(api.logout).mockResolvedValue()],
    ['通信に失敗した', () => vi.mocked(api.logout).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '文言'))],
  ])('%sときも、画面上はログアウトする', async (_label, arrange) => {
    arrange()
    vi.mocked(api.refresh).mockResolvedValue(authResponse())
    renderProvider()
    await vi.waitFor(() => expect(state()).toHaveTextContent('alice'))

    await userEvent.click(screen.getByRole('button', { name: 'logout' }))

    await vi.waitFor(() => expect(state()).toHaveTextContent('未ログイン'))
    expect(api.logout).toHaveBeenCalledTimes(1)
  })

  test('updateUser で、ログイン中のユーザーを新しいものにする', async () => {
    vi.mocked(api.refresh).mockResolvedValue(authResponse())
    renderProvider()
    await vi.waitFor(() => expect(state()).toHaveTextContent('alice'))

    await userEvent.click(screen.getByRole('button', { name: 'update' }))

    expect(state()).toHaveTextContent('alice2')
  })
})

describe('AuthProvider：再発行もできずに 401 になったとき', () => {
  test('通信の仕組みに「ログアウト状態にする処理」を登録し、呼ばれたら未ログインにする。画面を閉じたら登録を外す', async () => {
    vi.mocked(api.refresh).mockResolvedValue(authResponse())
    const { unmount } = renderProvider()
    await vi.waitFor(() => expect(state()).toHaveTextContent('alice'))

    const handler = vi.mocked(api.setUnauthorizedHandler).mock.calls[0][0]
    act(() => handler?.())
    expect(state()).toHaveTextContent('未ログイン')

    unmount()
    expect(api.setUnauthorizedHandler).toHaveBeenLastCalledWith(null)
  })
})
