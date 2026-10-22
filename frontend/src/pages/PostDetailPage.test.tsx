// @vitest-environment jsdom
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link } from 'react-router'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, createComment, deletePost, fetchComments, fetchPost, likePost, updatePost } from '../api/client'
import type { Post } from '../api/types'
import { aComment, aPage, aPost, deferred, renderWithRouter } from '../test/helpers'
import { PostDetailPage } from './PostDetailPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  fetchPost: vi.fn(),
  fetchComments: vi.fn(),
  createComment: vi.fn(),
  likePost: vi.fn(),
  updatePost: vi.fn(),
  deletePost: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(fetchPost).mockReset()
  vi.mocked(fetchComments).mockReset().mockResolvedValue(aPage([]))
  vi.mocked(createComment).mockReset()
  vi.mocked(likePost).mockReset()
  vi.mocked(updatePost).mockReset()
  vi.mocked(deletePost).mockReset()
})

const myPost = aPost({ id: 10, content: '詳細の本文', mine: true, commentCount: 0 })

function renderDetail(route: string | { pathname: string; state: unknown } = '/posts/10') {
  return renderWithRouter(
    <>
      <Link to="/posts/11">別の投稿へ</Link>
      <PostDetailPage />
    </>,
    { route, path: '/posts/:postId' },
  )
}

const location = () => screen.getByTestId('location')
const locationState = () => JSON.parse(screen.getByTestId('location-state').textContent ?? 'null')

describe('PostDetailPage：読み込み（状態遷移）', () => {
  test('読み込み中 → 投稿カードとコメント欄', async () => {
    const response = deferred<Post>()
    vi.mocked(fetchPost).mockReturnValue(response.promise)
    renderDetail()

    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    response.resolve(myPost)

    expect(await screen.findByText('詳細の本文')).toBeInTheDocument()
    expect(fetchPost).toHaveBeenCalledWith(10)
    expect(screen.getByRole('region', { name: 'コメント' })).toBeInTheDocument()
    expect(fetchComments).toHaveBeenCalledWith(10)
  })

  // 同値分割：正の整数 / 0 / 負の数 / 小数 / 数字でない
  test.each(['0', '-1', '1.5', 'abc'])('投稿 ID が「%s」なら、通信せずに「この投稿は見つかりません」', (postId) => {
    renderDetail(`/posts/${postId}`)

    expect(screen.getByText('この投稿は見つかりません')).toBeInTheDocument()
    expect(fetchPost).not.toHaveBeenCalled()
  })

  test.each([
    [new ApiError(404, 'POST_NOT_FOUND', '文言'), 'この投稿は見つかりません'],
    [new ApiError(0, 'NETWORK_ERROR', '文言'), '投稿を読み込めませんでした。時間をおいてもう一度お試しください'],
  ])('読み込みに失敗したら、種類に応じた文言を出す（%s）', async (error, message) => {
    vi.mocked(fetchPost).mockRejectedValue(error)
    renderDetail()

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(fetchComments).not.toHaveBeenCalled()
  })

  test('別の投稿へ移動したら、読み込み中に戻ってその投稿を読み込む。前の投稿の結果があとから届いても使わない', async () => {
    const first = deferred<Post>()
    const second = deferred<Post>()
    vi.mocked(fetchPost).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    renderDetail()

    await userEvent.click(screen.getByRole('link', { name: '別の投稿へ' }))
    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    first.resolve(aPost({ id: 10, content: '前の投稿' }))
    second.resolve(aPost({ id: 11, content: '次の投稿' }))

    expect(await screen.findByText('次の投稿')).toBeInTheDocument()
    expect(screen.queryByText('前の投稿')).not.toBeInTheDocument()
    expect(fetchPost).toHaveBeenLastCalledWith(11)
  })

  test('「←」は、URL を直接開いたとき（戻る先がない）はタイムラインへ', async () => {
    vi.mocked(fetchPost).mockResolvedValue(myPost)
    renderDetail()

    await userEvent.click(screen.getByRole('button', { name: '戻る' }))

    expect(location()).toHaveTextContent(/^\/$/)
  })
})

describe('PostDetailPage：コメント欄との連携', () => {
  test('投稿カードの 💬 から来たら（state に focusComment）、コメントの入力欄にフォーカスを当てる', async () => {
    vi.mocked(fetchPost).mockResolvedValue(myPost)
    renderDetail({ pathname: '/posts/10', state: { focusComment: true } })

    await vi.waitFor(() => expect(screen.getByRole('textbox', { name: 'コメント' })).toHaveFocus())
  })

  test('ふつうに開いたら、フォーカスは当てない', async () => {
    vi.mocked(fetchPost).mockResolvedValue(myPost)
    renderDetail()

    expect(await screen.findByRole('textbox', { name: 'コメント' })).not.toHaveFocus()
  })

  test('コメントしたら、投稿カードのコメント数も変える', async () => {
    vi.mocked(fetchPost).mockResolvedValue(myPost)
    vi.mocked(createComment).mockResolvedValue({ ...aComment({ id: 1, content: 'いいですね', mine: true }), commentCount: 1 })
    renderDetail()

    await userEvent.type(await screen.findByRole('textbox', { name: 'コメント' }), 'いいですね')
    await userEvent.click(screen.getByRole('button', { name: 'コメントする' }))

    await vi.waitFor(() => expect(screen.getByRole('link', { name: 'コメント（1件）' })).toBeInTheDocument())
  })
})

describe('PostDetailPage：いいね・編集・削除', () => {
  beforeEach(() => {
    vi.mocked(fetchPost).mockResolvedValue(myPost)
  })

  test('いいねしたら、投稿カードの表示を変える', async () => {
    vi.mocked(likePost).mockResolvedValue({ likeCount: 6, likedByMe: true })
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'いいね' }))

    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'いいねを取り消す' })).toHaveTextContent('♥6'))
  })

  test('いいねに失敗したら、エラーを知らせる', async () => {
    vi.mocked(likePost).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '文言'))
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'いいね' }))

    expect(await screen.findByRole('status')).toHaveTextContent('いいねに失敗しました')
  })

  test('編集して保存したら、新しい本文を出す', async () => {
    vi.mocked(updatePost).mockResolvedValue({ ...myPost, content: '直した本文' })
    renderDetail()

    await userEvent.click(await screen.findByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '編集' }))
    await userEvent.clear(screen.getByRole('textbox', { name: '本文' }))
    await userEvent.type(screen.getByRole('textbox', { name: '本文' }), '直した本文')
    await userEvent.click(screen.getByRole('button', { name: '保存する' }))

    expect(await screen.findByText('直した本文')).toBeInTheDocument()
  })

  async function deleteThePost() {
    renderDetail()
    await userEvent.click(await screen.findByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: '削除する' }))
  }

  test('削除したら、タイムラインへ戻り、お知らせを渡す', async () => {
    vi.mocked(deletePost).mockResolvedValue()

    await deleteThePost()

    await vi.waitFor(() => expect(location()).toHaveTextContent(/^\/$/))
    expect(locationState()).toEqual({ notice: '投稿を削除しました' })
  })

  test('すでに削除されていたら、タイムラインへ戻り、自分が削除したのではないとエラーで知らせる', async () => {
    vi.mocked(deletePost).mockRejectedValue(new ApiError(404, 'POST_NOT_FOUND', '文言'))

    await deleteThePost()

    await vi.waitFor(() => expect(location()).toHaveTextContent(/^\/$/))
    expect(locationState()).toEqual({ notice: 'この投稿はすでに削除されています', noticeError: true })
  })
})
