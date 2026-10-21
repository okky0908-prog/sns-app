// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { likePost } from '../api/client'
import type { Post, UserSummary } from '../api/types'
import { AuthContext, type AuthContextValue } from '../auth/authContext'
import { aPost, aUser, NOW, renderWithRouter } from '../test/helpers'
import { AppLayout } from './AppLayout'
import { PostCard } from './PostCard'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  likePost: vi.fn(),
}))

beforeEach(() => {
  // 「◯分前」の表示を固定するため、今の時刻を決める
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('PostCard：投稿カード', () => {
  const handlers = () => ({ onEdit: vi.fn(), onDelete: vi.fn(), onLikeChange: vi.fn(), onLikeError: vi.fn() })
  const card = (post: Post, h = handlers(), detail = false) => {
    renderWithRouter(<PostCard post={post} detail={detail} {...h} />)
    return h
  }

  test('投稿者・本文・投稿日時を出し、投稿者はプロフィール、日時は投稿詳細へのリンク', () => {
    card(aPost({ id: 10, author: aUser({ username: 'bobby', displayName: 'ボブ' }), content: '本文です' }))

    expect(screen.getByRole('link', { name: 'ボブ' })).toHaveAttribute('href', '/users/bobby')
    expect(screen.getByRole('link', { name: '@bobby' })).toHaveAttribute('href', '/users/bobby')
    expect(screen.getByRole('link', { name: '5分前' })).toHaveAttribute('href', '/posts/10')
    expect(screen.getByText('本文です')).toBeInTheDocument()
  })

  test('「編集済み」は、編集した投稿にだけ出す', () => {
    card(aPost({ editedAt: null }))
    expect(screen.queryByText(/編集済み/)).not.toBeInTheDocument()
  })

  test('編集した投稿には「編集済み」を出す', () => {
    card(aPost({ editedAt: NOW.toISOString() }))
    expect(screen.getByText('· 編集済み')).toBeInTheDocument()
  })

  test('本文のない投稿（画像だけ）は、本文の段落を出さない', () => {
    const { container } = render(
      <MemoryRouter>
        <PostCard post={aPost({ content: '', images: [{ url: 'https://cdn/a.png', sortOrder: 1 }] })} {...handlers()} />
      </MemoryRouter>,
    )
    expect(container.querySelectorAll('p')).toHaveLength(0)
  })

  test.each([
    [0, 'コメント（0件）', ''],
    [3, 'コメント（3件）', '3'],
  ])('コメント数 %i は「%s」と読み上げ、数字は0なら出さない', (commentCount, name, text) => {
    card(aPost({ commentCount }))

    expect(screen.getByRole('link', { name })).toHaveTextContent(`💬${text}`)
  })

  test('他人の投稿には「…」メニューを出さない', () => {
    card(aPost({ mine: false }))
    expect(screen.queryByRole('button', { name: 'メニュー' })).not.toBeInTheDocument()
  })

  test('自分の投稿は「…」メニューから編集・削除を選べ、選んだら閉じる', async () => {
    const post = aPost({ mine: true })
    const h = card(post)
    const menu = screen.getByRole('button', { name: 'メニュー' })

    await userEvent.click(menu)
    expect(menu).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(screen.getByRole('menuitem', { name: '編集' }))
    expect(h.onEdit).toHaveBeenCalledWith(post)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    await userEvent.click(menu)
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    expect(h.onDelete).toHaveBeenCalledWith(post)
  })

  test('メニューの外をクリックしたら閉じる', async () => {
    card(aPost({ mine: true }))

    await userEvent.click(screen.getByRole('button', { name: 'メニュー' }))
    await userEvent.click(document.body)

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  test('カードの余白を押すと投稿詳細へ移動する', async () => {
    card(aPost({ id: 10 }))

    await userEvent.click(screen.getByText(aPost().content))

    expect(screen.getByTestId('location')).toHaveTextContent('/posts/10')
  })

  test('投稿詳細の中では、カードを押しても移動しない', async () => {
    card(aPost({ id: 10 }), handlers(), true)

    await userEvent.click(screen.getByText(aPost().content))

    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
  })

  test('いいねのボタンを押しても、投稿詳細へは移動しない', async () => {
    vi.mocked(likePost).mockReturnValue(new Promise(() => {}))
    const h = card(aPost({ id: 10 }))

    await userEvent.click(screen.getByRole('button', { name: 'いいね' }))

    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
    expect(h.onLikeChange).toHaveBeenCalledWith(10, { likeCount: 1, likedByMe: true })
  })
})

describe('AppLayout：ログイン後の共通ヘッダー', () => {
  const logout = vi.fn()

  function renderLayout(user: UserSummary | null) {
    const value: AuthContextValue = {
      user,
      loading: false,
      login: vi.fn(),
      signup: vi.fn(),
      logout,
      updateUser: vi.fn(),
    }
    function Location() {
      const location = useLocation()
      return <output data-testid="location">{location.pathname + location.search}</output>
    }
    render(
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="*" element={<p>画面の中身</p>} />
            </Route>
          </Routes>
          <Location />
        </MemoryRouter>
      </AuthContext.Provider>,
    )
  }

  beforeEach(() => {
    logout.mockReset().mockResolvedValue(undefined)
  })

  test('ログイン中のユーザーの表示名を出し、押すと自分のプロフィールへ（@ユーザー名も読み上げる）', async () => {
    renderLayout(aUser({ username: 'alice', displayName: 'アリス' }))

    const account = screen.getByRole('link', { name: 'アリス（@alice）のプロフィール' })
    expect(account).toHaveTextContent('アリス')
    expect(screen.getByRole('link', { name: 'プロフィール' })).toHaveAttribute('href', '/users/alice')
    expect(screen.getByText('画面の中身')).toBeInTheDocument()
  })

  test.each([
    ['  yama  ', '/search?q=yama'],
    ['@山田 太郎', '/search?q=%40%E5%B1%B1%E7%94%B0%20%E5%A4%AA%E9%83%8E'],
    ['   ', '/search'],
  ])('検索に「%s」と入れて Enter → %s を開き、入力欄を空にする', async (keyword, url) => {
    vi.useRealTimers() // 入力の待ち時間を本物の時計で動かす
    renderLayout(aUser())
    const input = screen.getByRole('searchbox', { name: 'ユーザーを検索' })

    await userEvent.type(input, `${keyword}{Enter}`)

    expect(screen.getByTestId('location')).toHaveTextContent(url)
    expect(input).toHaveValue('')
  })

  test('ログアウトしたら、ログイン画面へ移動する', async () => {
    vi.useRealTimers()
    renderLayout(aUser())

    await userEvent.click(screen.getByRole('button', { name: 'ログアウト' }))

    expect(logout).toHaveBeenCalledTimes(1)
    await vi.waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/login'))
  })

  test('ログイン中のユーザーがいなければ、プロフィールへのリンクを出さない', () => {
    renderLayout(null)

    expect(screen.queryByRole('link', { name: 'プロフィール' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ログアウト' })).toBeInTheDocument()
  })
})
