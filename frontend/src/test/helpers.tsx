// UI 部品・画面のテストで共通して使うもの
import { act, render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes, useLocation, type InitialEntry } from 'react-router'
import { vi } from 'vitest'
import type { Comment, CursorPage, Post, Profile, UserListItem, UserSummary } from '../api/types'
import { AuthContext, type AuthContextValue } from '../auth/authContext'

interface RenderOptions {
  /** 最初に開く URL（state 付きで開くときは { pathname, state }） */
  route?: InitialEntry
  /** 画面の URL の形（useParams で :postId などを読む画面のとき） */
  path?: string
  /** ログイン状態（useAuth を使う画面のとき） */
  auth?: AuthContextValue
}

/** 画面の移動（Link・useNavigate）を使う部品を、Router の中で描画する。今の URL は screen.getByTestId('location') で読める */
export function renderWithRouter(ui: ReactElement, { route = '/', path = '*', auth }: RenderOptions = {}) {
  const tree = (
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path={path} element={ui} />
        {path !== '*' && <Route path="*" element={null} />}
      </Routes>
      <CurrentLocation />
    </MemoryRouter>
  )
  return render(auth ? <AuthContext.Provider value={auth}>{tree}</AuthContext.Provider> : tree)
}

/** 移動したかを確かめるため、今の URL と、移動のときに渡した state を画面に出す */
function CurrentLocation() {
  const location = useLocation()
  return (
    <>
      {/* <output> だとお知らせ（role="status"）と区別しにくいので、ただの span にする */}
      <span data-testid="location">{location.pathname + location.search}</span>
      <span data-testid="location-state">{JSON.stringify(location.state)}</span>
    </>
  )
}

/** ログイン状態の偽物（login などは何もしない関数。必要ならテストで上書きする） */
export function anAuth(overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    user: aUser(),
    loading: false,
    login: vi.fn(),
    signup: vi.fn(),
    logout: vi.fn(),
    updateUser: vi.fn(),
    ...overrides,
  }
}

/**
 * jsdom には IntersectionObserver がないので、作られた見張りを記録する偽物を置く（vi.stubGlobal で差し替えて使う）。
 * 無限スクロールの「一番下の目印が画面に近づいた」は scrollToBottom() で起こす
 */
export class FakeIntersectionObserver {
  static active = new Set<FakeIntersectionObserver>()
  readonly callback: (entries: { isIntersecting: boolean }[]) => void
  constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
    this.callback = callback
  }
  observe() {
    FakeIntersectionObserver.active.add(this)
  }
  disconnect() {
    FakeIntersectionObserver.active.delete(this)
  }
  /** 今見張っている目印を、すべて画面に近づけたことにする */
  static scrollToBottom() {
    act(() => [...FakeIntersectionObserver.active].forEach((observer) => observer.callback([{ isIntersecting: true }])))
  }
}

/** カーソル方式の1ページ分（nextCursor があれば続きがある） */
export function aPage<T>(items: T[], nextCursor: string | null = null): CursorPage<T> {
  return { items, nextCursor, hasNext: nextCursor !== null }
}

/** あとで結果を返す通信（通信中の表示を確かめるため）。resolve・reject を呼ぶまで終わらない */
export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

// ===== テスト用のデータ =====

export const NOW = new Date('2026-10-22T12:00:00+09:00')

export function aUser(overrides: Partial<UserSummary> = {}): UserSummary {
  return { id: 2, username: 'bobby', displayName: 'ボブ', iconUrl: null, ...overrides }
}

export function aPost(overrides: Partial<Post> = {}): Post {
  return {
    id: 10,
    content: '今日はSpring Bootの勉強をしました。',
    images: [],
    author: aUser(),
    likeCount: 0,
    likedByMe: false,
    commentCount: 0,
    editedAt: null,
    createdAt: new Date(NOW.getTime() - 5 * 60 * 1000).toISOString(),
    mine: false,
    ...overrides,
  }
}

export function aComment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: 100,
    content: 'わかりやすいです！',
    author: aUser(),
    createdAt: NOW.toISOString(),
    mine: false,
    ...overrides,
  }
}

export function aListItem(overrides: Partial<UserListItem> = {}): UserListItem {
  return { ...aUser(), bio: null, followedByMe: false, me: false, ...overrides }
}

export function aProfile(overrides: Partial<Profile> = {}): Profile {
  return {
    id: 2,
    username: 'bobby',
    displayName: 'ボブ',
    iconUrl: null,
    bio: 'Javaを勉強中です',
    followingCount: 3,
    followerCount: 8,
    followedByMe: false,
    me: false,
    ...overrides,
  }
}
