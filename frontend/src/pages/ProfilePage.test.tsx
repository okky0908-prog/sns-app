// @vitest-environment jsdom
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link } from 'react-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, deletePost, fetchProfile, fetchUserPosts, followUser, likePost, unlikePost, updatePost } from '../api/client'
import type { CursorPage, Post, Profile } from '../api/types'
import { aPage, aPost, aProfile, deferred, FakeIntersectionObserver, renderWithRouter } from '../test/helpers'
import { ProfileRoute } from './ProfilePage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  fetchProfile: vi.fn(),
  fetchUserPosts: vi.fn(),
  followUser: vi.fn(),
  likePost: vi.fn(),
  unlikePost: vi.fn(),
  deletePost: vi.fn(),
  updatePost: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(fetchProfile).mockReset()
  vi.mocked(fetchUserPosts).mockReset()
  vi.mocked(followUser).mockReset()
  vi.mocked(likePost).mockReset()
  vi.mocked(unlikePost).mockReset()
  vi.mocked(deletePost).mockReset()
  vi.mocked(updatePost).mockReset()
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
  FakeIntersectionObserver.active.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const posts = (...ids: number[]) => ids.map((id) => aPost({ id, content: `投稿${id}` }))

function renderProfile(route: string | { pathname: string; state: unknown } = '/users/bobby') {
  return renderWithRouter(
    <>
      <Link to="/users/carol">キャロルへ</Link>
      <Link to="/users/BOBBY">大文字のボブへ</Link>
      <ProfileRoute />
    </>,
    { route, path: '/users/:username' },
  )
}

const profileRegion = () => screen.getByRole('region', { name: 'プロフィール' })
const shownPosts = () =>
  within(screen.getByRole('region', { name: '投稿一覧' }))
    .queryAllByText(/^投稿\d+$/)
    .map((p) => p.textContent)

describe('ProfilePage：プロフィール（状態遷移）', () => {
  test('読み込み中 → 表示名・@ユーザー名・自己紹介・フォロー数。数を押すとフォロー一覧へ', async () => {
    const response = deferred<Profile>()
    vi.mocked(fetchProfile).mockReturnValue(response.promise)
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    renderProfile()

    expect(screen.getByRole('heading', { name: 'プロフィール' })).toBeInTheDocument()
    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    // プロフィールを待たずに、投稿一覧も同時に取りに行く
    expect(fetchUserPosts).toHaveBeenCalledWith('bobby')
    response.resolve(aProfile({ username: 'bobby', displayName: 'ボブ', bio: 'Javaを勉強中です', followingCount: 3, followerCount: 8 }))

    expect(await screen.findByRole('heading', { name: 'ボブ' })).toBeInTheDocument()
    const region = profileRegion()
    expect(within(region).getByText('@bobby')).toBeInTheDocument()
    expect(within(region).getByText('Javaを勉強中です')).toBeInTheDocument()
    expect(within(region).getByRole('link', { name: '3 フォロー中' })).toHaveAttribute('href', '/users/bobby/following')
    expect(within(region).getByRole('link', { name: '8 フォロワー' })).toHaveAttribute('href', '/users/bobby/followers')
  })

  test('自己紹介がなければ出さない', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile({ bio: null }))
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    renderProfile()

    await screen.findByRole('region', { name: 'プロフィール' })
    expect(within(profileRegion()).queryByText('Javaを勉強中です')).not.toBeInTheDocument()
  })

  test.each([
    [new ApiError(404, 'USER_NOT_FOUND', '文言'), 'このアカウントは存在しません'],
    [new ApiError(0, 'NETWORK_ERROR', '文言'), 'プロフィールを読み込めませんでした。時間をおいてもう一度お試しください'],
  ])('読み込みに失敗したら、種類に応じた文言を出し、投稿一覧も出さない（%s）', async (error, message) => {
    vi.mocked(fetchProfile).mockRejectedValue(error)
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage(posts(1)))
    renderProfile()

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '投稿一覧' })).not.toBeInTheDocument()
  })

  test('自分のプロフィールなら「プロフィールを編集」を出し、フォローボタンは出さない', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile({ me: true }))
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    renderProfile()

    expect(await screen.findByRole('link', { name: 'プロフィールを編集' })).toHaveAttribute('href', '/settings/profile')
    expect(screen.queryByRole('button', { name: /フォロー/ })).not.toBeInTheDocument()
  })

  test('他人のプロフィールならフォローボタンを出し、フォローしたらフォロワー数も変える', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile({ followedByMe: false, followerCount: 8 }))
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    vi.mocked(followUser).mockResolvedValue({ following: true, followerCount: 9 })
    renderProfile()

    await userEvent.click(await screen.findByRole('button', { name: 'フォローする' }))

    await vi.waitFor(() => expect(within(profileRegion()).getByRole('link', { name: '9 フォロワー' })).toBeInTheDocument())
    expect(screen.queryByRole('link', { name: 'プロフィールを編集' })).not.toBeInTheDocument()
  })

  test('フォローに失敗したら、エラーを知らせる', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile())
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    vi.mocked(followUser).mockRejectedValue(new ApiError(404, 'USER_NOT_FOUND', '文言'))
    renderProfile()

    await userEvent.click(await screen.findByRole('button', { name: 'フォローする' }))

    expect(await screen.findByRole('status')).toHaveTextContent('このユーザーは見つかりません')
  })

  test('プロフィール編集から戻ってきたときのお知らせを出し、state は消す', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile({ me: true }))
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    renderProfile({ pathname: '/users/bobby', state: { notice: 'プロフィールを更新しました' } })

    expect(await screen.findByRole('status')).toHaveTextContent('プロフィールを更新しました')
    expect(screen.getByTestId('location-state')).toHaveTextContent('null')
  })

  test('ほかの人のプロフィールへ移動したら、作り直して読み込み直す', async () => {
    vi.mocked(fetchProfile)
      .mockResolvedValueOnce(aProfile({ username: 'bobby', displayName: 'ボブ' }))
      .mockResolvedValueOnce(aProfile({ username: 'carol', displayName: 'キャロル' }))
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    renderProfile()
    await screen.findByRole('heading', { name: 'ボブ' })

    await userEvent.click(screen.getByRole('link', { name: 'キャロルへ' }))

    expect(await screen.findByRole('heading', { name: 'キャロル' })).toBeInTheDocument()
    expect(fetchProfile).toHaveBeenLastCalledWith('carol')
  })

  test('URL のユーザー名は大文字・小文字を区別しないので、大文字にしただけなら画面を作り直さない（読み込み中に戻らない）', async () => {
    vi.mocked(fetchProfile).mockResolvedValueOnce(aProfile()).mockReturnValueOnce(new Promise(() => {}))
    vi.mocked(fetchUserPosts).mockResolvedValueOnce(aPage(posts(1))).mockReturnValueOnce(new Promise(() => {}))
    renderProfile()
    await screen.findByRole('heading', { name: 'ボブ' })

    await userEvent.click(screen.getByRole('link', { name: '大文字のボブへ' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/users/BOBBY')
    expect(screen.getByRole('heading', { name: 'ボブ' })).toBeInTheDocument()
    expect(screen.queryByText('読み込み中…')).not.toBeInTheDocument()
    expect(shownPosts()).toEqual(['投稿1'])
  })

  test('読み込みの途中で別の人へ移動したら、前の人の結果があとから届いても使わない', async () => {
    const bobby = deferred<Profile>()
    const bobbyPosts = deferred<CursorPage<Post>>()
    vi.mocked(fetchProfile).mockReturnValueOnce(bobby.promise).mockResolvedValueOnce(aProfile({ displayName: 'キャロル' }))
    vi.mocked(fetchUserPosts).mockReturnValueOnce(bobbyPosts.promise).mockResolvedValueOnce(aPage([]))
    renderProfile()

    await userEvent.click(screen.getByRole('link', { name: 'キャロルへ' }))
    await screen.findByRole('heading', { name: 'キャロル' })
    bobby.resolve(aProfile({ displayName: 'ボブ' }))
    bobbyPosts.resolve(aPage(posts(1)))

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.getByRole('heading', { name: 'キャロル' })).toBeInTheDocument()
    expect(shownPosts()).toEqual([])
  })

  test('「←」は、URL を直接開いたとき（戻る先がない）はタイムラインへ', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile())
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    renderProfile()

    await userEvent.click(screen.getByRole('button', { name: '戻る' }))

    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
  })
})

describe('ProfilePage：投稿一覧', () => {
  test.each([
    [true, 'まだ投稿がありません。最初の投稿をしてみましょう'],
    [false, 'まだ投稿がありません'],
  ])('0件なら、自分のプロフィール（%s）かどうかで文言を変える', async (me, message) => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile({ me }))
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([]))
    renderProfile()

    expect(await screen.findByText(message)).toBeInTheDocument()
  })

  test('投稿一覧の読み込み中・失敗の表示（プロフィールは出したまま）', async () => {
    const response = deferred<CursorPage<Post>>()
    vi.mocked(fetchProfile).mockResolvedValue(aProfile())
    vi.mocked(fetchUserPosts).mockReturnValue(response.promise)
    renderProfile()

    await screen.findByRole('region', { name: 'プロフィール' })
    expect(within(screen.getByRole('region', { name: '投稿一覧' })).getByText('読み込み中…')).toBeInTheDocument()
    response.reject(new ApiError(0, 'NETWORK_ERROR', '文言'))

    expect(await screen.findByText('投稿を読み込めませんでした')).toBeInTheDocument()
    expect(profileRegion()).toBeInTheDocument()
  })

  test('下まで来たら続きを読み込んで後ろにつなげ（重なりは除く）、続きがなくなったら終わりの表示', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile())
    vi.mocked(fetchUserPosts).mockResolvedValueOnce(aPage(posts(5, 4), 'c1')).mockResolvedValueOnce(aPage(posts(4, 3)))
    renderProfile()
    await vi.waitFor(() => expect(shownPosts()).toEqual(['投稿5', '投稿4']))
    expect(screen.queryByText('これ以上の投稿はありません')).not.toBeInTheDocument()

    FakeIntersectionObserver.scrollToBottom()

    await vi.waitFor(() => expect(shownPosts()).toEqual(['投稿5', '投稿4', '投稿3']))
    expect(fetchUserPosts).toHaveBeenLastCalledWith('bobby', 'c1')
    expect(screen.getByText('これ以上の投稿はありません')).toBeInTheDocument()
  })

  test('続きの読み込みに失敗したら、再試行のボタンで読み込む', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile())
    vi.mocked(fetchUserPosts)
      .mockResolvedValueOnce(aPage(posts(5), 'c1'))
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言'))
      .mockResolvedValueOnce(aPage(posts(4)))
    renderProfile()
    await vi.waitFor(() => expect(shownPosts()).toEqual(['投稿5']))

    FakeIntersectionObserver.scrollToBottom()
    expect(await screen.findByText('読み込みに失敗しました')).toBeInTheDocument()
    expect(FakeIntersectionObserver.active.size).toBe(0)
    await userEvent.click(screen.getByRole('button', { name: '再試行' }))

    await vi.waitFor(() => expect(shownPosts()).toEqual(['投稿5', '投稿4']))
  })

  test('いいねしたら、その投稿の表示を変える。失敗したらエラーを知らせる', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile())
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage(posts(1)))
    vi.mocked(likePost)
      .mockResolvedValueOnce({ likeCount: 2, likedByMe: true })
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言'))
    vi.mocked(unlikePost).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言'))
    renderProfile()

    await userEvent.click(await screen.findByRole('button', { name: 'いいね' }))
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'いいねを取り消す' })).toHaveTextContent('♥2'))

    await userEvent.click(screen.getByRole('button', { name: 'いいねを取り消す' }))
    expect(await screen.findByRole('status')).toHaveTextContent('いいねに失敗しました')
  })

  test('自分の投稿を編集したら、一覧のその投稿だけ新しい本文にする', async () => {
    vi.mocked(updatePost).mockResolvedValue(aPost({ id: 1, content: '投稿100', mine: true }))
    vi.mocked(fetchProfile).mockResolvedValue(aProfile({ me: true }))
    vi.mocked(fetchUserPosts).mockResolvedValue(
      aPage([aPost({ id: 1, content: '投稿1', mine: true }), aPost({ id: 2, content: '投稿2', mine: true })]),
    )
    renderProfile()
    await vi.waitFor(() => expect(shownPosts()).toEqual(['投稿1', '投稿2']))

    await userEvent.click(screen.getAllByRole('button', { name: 'メニュー' })[0])
    await userEvent.click(screen.getByRole('menuitem', { name: '編集' }))
    await userEvent.clear(screen.getByRole('textbox', { name: '本文' }))
    await userEvent.type(screen.getByRole('textbox', { name: '本文' }), '投稿100')
    await userEvent.click(screen.getByRole('button', { name: '保存する' }))

    await vi.waitFor(() => expect(shownPosts()).toEqual(['投稿100', '投稿2']))
    expect(updatePost).toHaveBeenCalledWith(1, '投稿100')
  })

  test('自分の投稿を削除したら、一覧から消す', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(aProfile({ me: true }))
    vi.mocked(fetchUserPosts).mockResolvedValue(aPage([aPost({ id: 1, content: '投稿1', mine: true })]))
    vi.mocked(deletePost).mockResolvedValue()
    renderProfile()

    await userEvent.click(await screen.findByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: '削除する' }))

    await vi.waitFor(() => expect(shownPosts()).toEqual([]))
  })
})
