// UI 部品・画面のテストで共通して使うもの
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { Comment, Post, UserListItem, UserSummary } from '../api/types'

/** 画面の移動（Link・useNavigate）を使う部品を、Router の中で描画する。今の URL は「現在地:」の後ろに出す */
export function renderWithRouter(ui: ReactElement, { route = '/' }: { route?: string } = {}) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path="*" element={ui} />
      </Routes>
      <CurrentLocation />
    </MemoryRouter>,
  )
}

/** 移動したかを確かめるため、今の URL を画面に出す（テストでは screen.getByTestId('location') で読む） */
function CurrentLocation() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname + location.search}</output>
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
