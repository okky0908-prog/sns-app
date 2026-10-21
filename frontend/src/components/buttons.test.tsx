// @vitest-environment jsdom
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, followUser, likePost, unfollowUser, unlikePost } from '../api/client'
import type { FollowState, LikeState, UserListItem } from '../api/types'
import { aListItem, deferred, renderWithRouter } from '../test/helpers'
import { FollowButton } from './FollowButton'
import { LikeButton } from './LikeButton'
import { UserListRow } from './UserListRow'

// 通信は偽物にする（ApiError などはそのまま使う）
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  likePost: vi.fn(),
  unlikePost: vi.fn(),
  followUser: vi.fn(),
  unfollowUser: vi.fn(),
}))

const onError = vi.fn()

beforeEach(() => {
  vi.mocked(likePost).mockReset()
  vi.mocked(unlikePost).mockReset()
  vi.mocked(followUser).mockReset()
  vi.mocked(unfollowUser).mockReset()
  onError.mockReset()
})

/** 表示する値を持つ親（画面と同じように、onChange で受け取った値を表示に使う） */
function LikeHarness({ initial }: { initial: LikeState }) {
  const [state, setState] = useState(initial)
  return <LikeButton postId={10} {...state} onChange={(_id, next) => setState(next)} onError={onError} />
}

const likeButton = () => screen.getByRole('button', { name: /いいね/ })

describe('LikeButton：いいね（状態遷移）', () => {
  test('押すとすぐに表示を変え（楽観的更新）、通信の結果（ほかの人のいいねも含む数）で上書きする', async () => {
    const response = deferred<LikeState>()
    vi.mocked(likePost).mockReturnValue(response.promise)
    renderWithRouter(<LikeHarness initial={{ likeCount: 2, likedByMe: false }} />)

    await userEvent.click(likeButton())
    // 通信の結果を待たずに変わる
    expect(likeButton()).toHaveAccessibleName('いいねを取り消す')
    expect(likeButton()).toHaveAttribute('aria-pressed', 'true')
    expect(likeButton()).toHaveTextContent('♥3')

    response.resolve({ likeCount: 5, likedByMe: true })
    await vi.waitFor(() => expect(likeButton()).toHaveTextContent('♥5'))
    expect(likePost).toHaveBeenCalledWith(10)
  })

  test('いいね済みで押すと取り消す', async () => {
    vi.mocked(unlikePost).mockResolvedValue({ likeCount: 0, likedByMe: false })
    renderWithRouter(<LikeHarness initial={{ likeCount: 1, likedByMe: true }} />)

    await userEvent.click(likeButton())

    await vi.waitFor(() => expect(likeButton()).toHaveAccessibleName('いいね'))
    expect(unlikePost).toHaveBeenCalledWith(10)
  })

  test('いいね数が0なら数字を出さない', () => {
    renderWithRouter(<LikeHarness initial={{ likeCount: 0, likedByMe: false }} />)

    expect(likeButton()).toHaveTextContent(/^♡$/)
  })

  test.each([
    [new ApiError(404, 'POST_NOT_FOUND', '文言'), 'この投稿は削除されています'],
    [new ApiError(0, 'NETWORK_ERROR', '文言'), 'いいねに失敗しました'],
  ])('失敗したら押す前の表示に戻し、エラーを知らせる（%s）', async (error, message) => {
    vi.mocked(likePost).mockRejectedValue(error)
    renderWithRouter(<LikeHarness initial={{ likeCount: 2, likedByMe: false }} />)

    await userEvent.click(likeButton())

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith(message))
    expect(likeButton()).toHaveAccessibleName('いいね')
    expect(likeButton()).toHaveTextContent('♡2')
  })

  test('連打：送信中に「取り消し」と押されたら、最初の結果が返ってから取り消しを1回だけ送る', async () => {
    const first = deferred<LikeState>()
    vi.mocked(likePost).mockReturnValue(first.promise)
    vi.mocked(unlikePost).mockResolvedValue({ likeCount: 2, likedByMe: false })
    renderWithRouter(<LikeHarness initial={{ likeCount: 2, likedByMe: false }} />)

    await userEvent.click(likeButton()) // いいね（送信中）
    await userEvent.click(likeButton()) // 取り消し（まだ送らない）
    expect(unlikePost).not.toHaveBeenCalled()
    expect(likeButton()).toHaveAccessibleName('いいね')

    first.resolve({ likeCount: 3, likedByMe: true })

    await vi.waitFor(() => expect(unlikePost).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(likeButton()).toHaveTextContent('♡2'))
    expect(likePost).toHaveBeenCalledTimes(1)
  })

  test('連打：送信中に「取り消し → いいね」と押され、最後の状態がサーバーと同じなら送り直さない', async () => {
    const first = deferred<LikeState>()
    vi.mocked(likePost).mockReturnValue(first.promise)
    renderWithRouter(<LikeHarness initial={{ likeCount: 2, likedByMe: false }} />)

    await userEvent.click(likeButton())
    await userEvent.click(likeButton())
    await userEvent.click(likeButton())
    first.resolve({ likeCount: 3, likedByMe: true })

    await vi.waitFor(() => expect(likeButton()).toHaveTextContent('♥3'))
    expect(likePost).toHaveBeenCalledTimes(1)
    expect(unlikePost).not.toHaveBeenCalled()
  })
})

function FollowHarness({ initial, showCount = true }: { initial: FollowState; showCount?: boolean }) {
  const [state, setState] = useState(initial)
  return (
    <>
      <FollowButton
        username="bobby"
        following={state.following}
        followerCount={showCount ? state.followerCount : undefined}
        onChange={setState}
        onError={onError}
      />
      <output aria-label="フォロワー数">{state.followerCount}</output>
    </>
  )
}

const followButton = () => screen.getByRole('button', { name: /フォロー/ })
const followerCount = () => screen.getByRole('status', { name: 'フォロワー数' })

describe('FollowButton：フォロー（状態遷移）', () => {
  test('押すとすぐにフォロー中にしてフォロワー数を1増やし、通信中は押せない。結果で上書きする', async () => {
    const response = deferred<FollowState>()
    vi.mocked(followUser).mockReturnValue(response.promise)
    renderWithRouter(<FollowHarness initial={{ following: false, followerCount: 8 }} />)

    expect(followButton()).toHaveTextContent('フォローする')
    await userEvent.click(followButton())
    expect(followButton()).toHaveTextContent('フォロー中')
    expect(followButton()).toHaveAttribute('aria-pressed', 'true')
    expect(followButton()).toBeDisabled()
    expect(followerCount()).toHaveTextContent('9')

    response.resolve({ following: true, followerCount: 12 })
    await vi.waitFor(() => expect(followerCount()).toHaveTextContent('12'))
    expect(followButton()).toBeEnabled()
    expect(followUser).toHaveBeenCalledWith('bobby')
  })

  test('フォロー中は、マウスを乗せたときの「フォロー解除」も用意してあり、押すと解除する', async () => {
    vi.mocked(unfollowUser).mockResolvedValue({ following: false, followerCount: 7 })
    renderWithRouter(<FollowHarness initial={{ following: true, followerCount: 8 }} />)

    expect(followButton()).toHaveTextContent('フォロー中フォロー解除')
    await userEvent.click(followButton())

    await vi.waitFor(() => expect(followerCount()).toHaveTextContent('7'))
    expect(followButton()).toHaveTextContent('フォローする')
  })

  test('フォロワー数は0より小さくしない', async () => {
    vi.mocked(unfollowUser).mockReturnValue(new Promise(() => {})) // 返ってこない
    renderWithRouter(<FollowHarness initial={{ following: true, followerCount: 0 }} />)

    await userEvent.click(followButton())

    expect(followerCount()).toHaveTextContent('0')
  })

  test.each([
    [new ApiError(404, 'USER_NOT_FOUND', '文言'), 'このユーザーは見つかりません'],
    [new ApiError(500, 'INTERNAL_ERROR', '文言'), 'フォローの変更に失敗しました'],
  ])('失敗したら押す前に戻し、エラーを知らせる（%s）', async (error, message) => {
    vi.mocked(followUser).mockRejectedValue(error)
    renderWithRouter(<FollowHarness initial={{ following: false, followerCount: 8 }} />)

    await userEvent.click(followButton())

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith(message))
    expect(followButton()).toHaveTextContent('フォローする')
    expect(followerCount()).toHaveTextContent('8')
    expect(followButton()).toBeEnabled()
  })
})

describe('UserListRow：ユーザー一覧の1行', () => {
  const row = (user: UserListItem, onFollowChange = vi.fn()) =>
    renderWithRouter(
      <ul>
        <UserListRow user={user} onFollowChange={onFollowChange} onError={onError} />
      </ul>,
    )

  test('表示名・@ユーザー名・自己紹介を出し、それぞれプロフィールへのリンク', () => {
    row(aListItem({ displayName: 'ボブ', username: 'bobby', bio: 'Javaを勉強中です' }))

    expect(screen.getByRole('link', { name: 'ボブ' })).toHaveAttribute('href', '/users/bobby')
    expect(screen.getByRole('link', { name: '@bobby' })).toHaveAttribute('href', '/users/bobby')
    expect(screen.getByText('Javaを勉強中です')).toBeInTheDocument()
  })

  test('自己紹介がなければ出さない', () => {
    const { container } = row(aListItem({ bio: null }))

    expect(container.querySelector('p')).toBeNull()
  })

  test('自分の行にはフォローボタンを出さない', () => {
    row(aListItem({ me: true }))

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  test('フォローボタンを押すと、この行のユーザー ID と新しい状態を知らせる', async () => {
    vi.mocked(followUser).mockResolvedValue({ following: true, followerCount: 1 })
    const onFollowChange = vi.fn()
    row(aListItem({ id: 7, followedByMe: false }), onFollowChange)

    await userEvent.click(screen.getByRole('button', { name: 'フォローする' }))

    await vi.waitFor(() => expect(onFollowChange).toHaveBeenLastCalledWith(7, { following: true, followerCount: 1 }))
  })
})
