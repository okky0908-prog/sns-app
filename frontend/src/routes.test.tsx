// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import {
  fetchFollowList,
  fetchPost,
  fetchProfile,
  fetchTimeline,
  fetchUserPosts,
  searchUsers,
  type TimelineTab,
} from './api/client'
import { AuthContext } from './auth/authContext'
import { routes } from './router'
import { anAuth, FakeIntersectionObserver } from './test/helpers'

// 画面の中身ではなく「どの URL でどの画面を出すか」を確かめるので、通信は返ってこないままにする
vi.mock('./api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api/client')>()),
  fetchTimeline: vi.fn(),
  fetchPost: vi.fn(),
  fetchComments: vi.fn(() => new Promise(() => {})),
  fetchProfile: vi.fn(),
  fetchUserPosts: vi.fn(() => new Promise(() => {})),
  fetchFollowList: vi.fn(),
  searchUsers: vi.fn(),
}))

const pending = () => new Promise<never>(() => {})

beforeEach(() => {
  vi.mocked(fetchTimeline).mockReset().mockImplementation(pending)
  vi.mocked(fetchPost).mockReset().mockImplementation(pending)
  vi.mocked(fetchProfile).mockReset().mockImplementation(pending)
  vi.mocked(fetchUserPosts).mockReset().mockImplementation(pending)
  vi.mocked(fetchFollowList).mockReset().mockImplementation(pending)
  vi.mocked(searchUsers).mockReset().mockImplementation(pending)
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

type LoginState = 'loading' | 'loggedIn' | 'loggedOut'

/** 本番と同じ画面の一覧（routes）で、指定した URL から開く */
function open(url: string, login: LoginState) {
  const router = createMemoryRouter(routes, { initialEntries: [url] })
  const auth = anAuth({
    loading: login === 'loading',
    user: login === 'loggedIn' ? anAuth().user : null,
  })
  render(
    <AuthContext.Provider value={auth}>
      <RouterProvider router={router} />
    </AuthContext.Provider>,
  )
  return router
}

describe('画面の振り分け（デシジョンテーブル：画面の種類 × ログイン状態）', () => {
  test.each<[string, LoginState, string]>([
    // ログインが必要な画面
    ['/', 'loggedOut', '/login'],
    ['/posts/10', 'loggedOut', '/login'],
    ['/settings/profile', 'loggedOut', '/login'],
    ['/', 'loggedIn', '/'],
    // ログイン・新規登録の画面
    ['/login', 'loggedOut', '/login'],
    ['/signup', 'loggedOut', '/signup'],
    ['/login', 'loggedIn', '/'],
    ['/signup', 'loggedIn', '/'],
    // どの画面にも当たらない URL はホームへ（未ログインなら、さらにログイン画面へ）
    ['/no-such-page', 'loggedIn', '/'],
    ['/no-such-page', 'loggedOut', '/login'],
  ])('%s を %s で開くと %s', async (url, login, expected) => {
    const router = open(url, login)

    await vi.waitFor(() => expect(router.state.location.pathname).toBe(expected))
  })

  test.each(['/', '/login'])('ログイン状態の確認中は、%s を開いても移動せず「読み込み中…」', (url) => {
    const router = open(url, 'loading')

    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(url)
  })
})

describe('URL ごとの画面（ログイン済み）', () => {
  test.each<[string, TimelineTab]>([
    ['/', 'following'],
    ['/all', 'all'],
  ])('%s はタイムライン（%s）', (url, tab) => {
    open(url, 'loggedIn')

    expect(screen.getByRole('heading', { name: 'ホーム' })).toBeInTheDocument()
    expect(fetchTimeline).toHaveBeenCalledWith(tab)
  })

  test('/posts/:postId は投稿詳細', () => {
    open('/posts/10', 'loggedIn')

    expect(screen.getByRole('heading', { name: '投稿' })).toBeInTheDocument()
    expect(fetchPost).toHaveBeenCalledWith(10)
  })

  test('/users/:username はプロフィール', () => {
    open('/users/alice', 'loggedIn')

    expect(screen.getByRole('heading', { name: 'プロフィール' })).toBeInTheDocument()
    expect(fetchProfile).toHaveBeenCalledWith('alice')
  })

  test.each([
    ['/users/alice/following', 'following'],
    ['/users/alice/followers', 'followers'],
  ])('%s はフォロー一覧（%s）', (url, kind) => {
    open(url, 'loggedIn')

    expect(screen.getByRole('heading', { name: '@alice' })).toBeInTheDocument()
    expect(fetchFollowList).toHaveBeenCalledWith('alice', kind)
  })

  test('/settings/profile はプロフィール編集', () => {
    open('/settings/profile', 'loggedIn')

    expect(fetchProfile).toHaveBeenCalledWith(anAuth().user?.username)
  })

  test('/search はユーザー検索', () => {
    open('/search?q=yama', 'loggedIn')

    expect(screen.getByRole('heading', { name: '「yama」の検索結果' })).toBeInTheDocument()
    expect(searchUsers).toHaveBeenCalledWith('yama')
  })

  test('ログイン後の画面には共通のヘッダーを出す', () => {
    open('/', 'loggedIn')

    expect(screen.getByRole('button', { name: 'ログアウト' })).toBeInTheDocument()
  })
})
