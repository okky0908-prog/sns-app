// @vitest-environment jsdom
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, fetchFollowList, followUser, type FollowListKind } from '../api/client'
import type { CursorPage, UserListItem } from '../api/types'
import { aListItem, aPage, deferred, FakeIntersectionObserver, renderWithRouter } from '../test/helpers'
import { FollowListRoute } from './FollowListPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  fetchFollowList: vi.fn(),
  followUser: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(fetchFollowList).mockReset()
  vi.mocked(followUser).mockReset()
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
  FakeIntersectionObserver.active.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const users = (...ids: number[]) => ids.map((id) => aListItem({ id, username: `user${id}`, displayName: `ユーザー${id}` }))

/** 本番と同じく、フォロー中・フォロワーの URL ごとに FollowListRoute を置く */
function renderList(url = '/users/bobby/following') {
  return renderWithRouter(
    <Routes>
      <Route path="/users/:username/following" element={<FollowListRoute kind="following" />} />
      <Route path="/users/:username/followers" element={<FollowListRoute kind="followers" />} />
      <Route path="*" element={<p>ほかの画面</p>} />
    </Routes>,
    { route: url },
  )
}

const shownUsers = () => screen.queryAllByRole('link', { name: /^ユーザー\d+$/ }).map((a) => a.textContent)

describe('FollowListPage：読み込み（状態遷移）', () => {
  test.each<[FollowListKind, string]>([
    ['following', 'フォロー中'],
    ['followers', 'フォロワー'],
  ])('読み込み中 → 一覧（%s）', async (kind, label) => {
    const response = deferred<CursorPage<UserListItem>>()
    vi.mocked(fetchFollowList).mockReturnValue(response.promise)
    renderList(`/users/bobby/${kind}`)

    expect(screen.getByRole('heading', { name: '@bobby' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: label })).toHaveAttribute('aria-busy', 'true')
    response.resolve(aPage(users(1, 2)))

    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2']))
    expect(fetchFollowList).toHaveBeenCalledWith('bobby', kind)
  })

  test.each<[FollowListKind, string]>([
    ['following', 'まだ誰もフォローしていません'],
    ['followers', 'まだフォロワーはいません'],
  ])('0件なら、一覧の種類（%s）に応じた文言', async (kind, message) => {
    vi.mocked(fetchFollowList).mockResolvedValue(aPage([]))
    renderList(`/users/bobby/${kind}`)

    expect(await screen.findByText(message)).toBeInTheDocument()
  })

  test.each([
    [new ApiError(404, 'USER_NOT_FOUND', '文言'), 'このアカウントは存在しません'],
    [new ApiError(0, 'NETWORK_ERROR', '文言'), '一覧を読み込めませんでした。時間をおいてもう一度お試しください'],
  ])('読み込みに失敗したら、種類に応じた文言を出す（%s）', async (error, message) => {
    vi.mocked(fetchFollowList).mockRejectedValue(error)
    renderList()

    expect(await screen.findByText(message)).toBeInTheDocument()
  })

  test('タブで切り替えたら、作り直してその一覧を1ページ目から読み込む（前の一覧の結果があとから届いても使わない）', async () => {
    const following = deferred<CursorPage<UserListItem>>()
    vi.mocked(fetchFollowList).mockReturnValueOnce(following.promise).mockResolvedValueOnce(aPage(users(9)))
    renderList('/users/bobby/following')

    await userEvent.click(screen.getByRole('link', { name: 'フォロワー' }))
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー9']))
    following.resolve(aPage(users(1)))

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(shownUsers()).toEqual(['ユーザー9'])
    expect(fetchFollowList).toHaveBeenLastCalledWith('bobby', 'followers')
    expect(screen.getByTestId('location')).toHaveTextContent('/users/bobby/followers')
  })

  test('「←」は、URL を直接開いたとき（戻る先がない）はそのユーザーのプロフィールへ', async () => {
    vi.mocked(fetchFollowList).mockResolvedValue(aPage([]))
    renderList('/users/bobby/followers')

    await userEvent.click(screen.getByRole('button', { name: '戻る' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/users/bobby')
  })
})

describe('FollowListPage：無限スクロール・フォロー', () => {
  test('下まで来たら続きを読み込んで後ろにつなげ（重なりは除く）、続きがなくなったら見張りをやめる', async () => {
    vi.mocked(fetchFollowList).mockResolvedValueOnce(aPage(users(1, 2), 'c1')).mockResolvedValueOnce(aPage(users(2, 3)))
    renderList()
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2']))

    FakeIntersectionObserver.scrollToBottom()

    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2', 'ユーザー3']))
    expect(fetchFollowList).toHaveBeenLastCalledWith('bobby', 'following', 'c1')
    expect(FakeIntersectionObserver.active.size).toBe(0)
  })

  test('続きの読み込みに失敗したら、再試行のボタンで読み込む', async () => {
    vi.mocked(fetchFollowList)
      .mockResolvedValueOnce(aPage(users(1), 'c1'))
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言'))
      .mockResolvedValueOnce(aPage(users(2)))
    renderList()
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1']))

    FakeIntersectionObserver.scrollToBottom()
    await userEvent.click(await screen.findByRole('button', { name: '再試行' }))

    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2']))
    expect(screen.queryByText('読み込みに失敗しました')).not.toBeInTheDocument()
  })

  test('フォローしたら、その行だけ「フォロー中」にする', async () => {
    vi.mocked(fetchFollowList).mockResolvedValue(aPage(users(1, 2)))
    vi.mocked(followUser).mockResolvedValue({ following: true, followerCount: 1 })
    renderList()
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2']))

    await userEvent.click(screen.getAllByRole('button', { name: 'フォローする' })[0])

    await vi.waitFor(() => expect(followUser).toHaveBeenCalledWith('user1'))
    expect(screen.getAllByRole('button', { name: /フォロー/ }).map((b) => b.getAttribute('aria-pressed'))).toEqual([
      'true',
      'false',
    ])
  })

  test('フォローに失敗したら、エラーを知らせる', async () => {
    vi.mocked(fetchFollowList).mockResolvedValue(aPage(users(1)))
    vi.mocked(followUser).mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', '文言'))
    renderList()

    await userEvent.click(await screen.findByRole('button', { name: 'フォローする' }))

    expect(await screen.findByRole('status')).toHaveTextContent('フォローの変更に失敗しました')
  })
})
