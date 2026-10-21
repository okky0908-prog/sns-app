// @vitest-environment jsdom
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, createComment, deleteComment, fetchComments, fetchPost } from '../api/client'
import type { Comment, CursorPage } from '../api/types'
import { aComment, aPost, aUser, deferred, renderWithRouter } from '../test/helpers'
import { CommentSection } from './CommentSection'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  fetchComments: vi.fn(),
  createComment: vi.fn(),
  deleteComment: vi.fn(),
  fetchPost: vi.fn(),
}))

const onCountChange = vi.fn()
const notify = vi.fn()

beforeEach(() => {
  vi.mocked(fetchComments).mockReset()
  vi.mocked(createComment).mockReset()
  vi.mocked(deleteComment).mockReset()
  vi.mocked(fetchPost).mockReset()
  onCountChange.mockReset()
  notify.mockReset()
})

const page = (items: Comment[], nextCursor: string | null = null): CursorPage<Comment> => ({
  items,
  nextCursor,
  hasNext: nextCursor !== null,
})

function renderSection(focusRequest: string | null = null) {
  return renderWithRouter(
    <CommentSection postId={10} focusRequest={focusRequest} onCountChange={onCountChange} notify={notify} />,
  )
}

/** 表示しているコメントの本文（上から順） */
const shownContents = () =>
  within(screen.getByRole('region', { name: 'コメント' }))
    .queryAllByRole('listitem')
    .map((item) => item.querySelector('p')?.textContent)
const input = () => screen.getByRole('textbox', { name: 'コメント' })
const submit = () => screen.getByRole('button', { name: /コメントする|送信中…/ })

describe('CommentSection：読み込み（状態遷移）', () => {
  test('読み込み中 → 古い順の一覧', async () => {
    const response = deferred<CursorPage<Comment>>()
    vi.mocked(fetchComments).mockReturnValue(response.promise)
    renderSection()

    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    response.resolve(page([aComment({ id: 1, content: '1番目' }), aComment({ id: 2, content: '2番目' })]))

    await vi.waitFor(() => expect(shownContents()).toEqual(['1番目', '2番目']))
    expect(fetchComments).toHaveBeenCalledWith(10)
  })

  test('0件なら「まだコメントはありません」', async () => {
    vi.mocked(fetchComments).mockResolvedValue(page([]))
    renderSection()

    expect(await screen.findByText('まだコメントはありません')).toBeInTheDocument()
  })

  test('読み込みに失敗したら再試行でき、再試行で読み込めたら一覧を出す', async () => {
    vi.mocked(fetchComments)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言'))
      .mockResolvedValueOnce(page([aComment({ content: '取り直した' })]))
    renderSection()

    await userEvent.click(await screen.findByRole('button', { name: '再試行' }))

    await vi.waitFor(() => expect(shownContents()).toEqual(['取り直した']))
  })

  test('続きがあれば「さらに表示」で後ろにつなげ、重なったものは除く', async () => {
    vi.mocked(fetchComments)
      .mockResolvedValueOnce(page([aComment({ id: 1, content: '1' }), aComment({ id: 2, content: '2' })], 'c1'))
      .mockResolvedValueOnce(page([aComment({ id: 2, content: '2' }), aComment({ id: 3, content: '3' })]))
    renderSection()

    await userEvent.click(await screen.findByRole('button', { name: 'さらに表示' }))

    await vi.waitFor(() => expect(shownContents()).toEqual(['1', '2', '3']))
    expect(fetchComments).toHaveBeenLastCalledWith(10, 'c1')
    // 続きがなくなったら、ボタンを出さない
    expect(screen.queryByRole('button', { name: 'さらに表示' })).not.toBeInTheDocument()
  })

  test.each([
    [new ApiError(404, 'POST_NOT_FOUND', '文言'), 'この投稿は削除されています'],
    [new ApiError(0, 'NETWORK_ERROR', '文言'), 'コメントを読み込めませんでした'],
  ])('続きの読み込みに失敗したら、エラーを知らせる（%s）', async (error, message) => {
    vi.mocked(fetchComments).mockResolvedValueOnce(page([aComment()], 'c1')).mockRejectedValueOnce(error)
    renderSection()

    await userEvent.click(await screen.findByRole('button', { name: 'さらに表示' }))

    await vi.waitFor(() => expect(notify).toHaveBeenCalledWith(message, true))
  })
})

describe('CommentSection：投稿', () => {
  beforeEach(() => {
    vi.mocked(fetchComments).mockResolvedValue(page([aComment({ id: 1, content: '読み込んだ' })]))
  })

  test.each([
    ['空', '', false],
    ['空白だけ', '   ', false],
    ['1文字', 'あ', true],
    ['280文字', 'あ'.repeat(280), true],
    ['281文字', 'あ'.repeat(281), false],
  ])('本文が%sなら、コメントできるか', async (_label, content, enabled) => {
    renderSection()
    await screen.findByText('読み込んだ')

    if (content) {
      await userEvent.click(input())
      await userEvent.paste(content)
    }

    if (enabled) expect(submit()).toBeEnabled()
    else expect(submit()).toBeDisabled()
  })

  test('投稿したら入力欄を空にし、一覧の後ろに足して、投稿後のコメント数を知らせる', async () => {
    vi.mocked(createComment).mockResolvedValue({ ...aComment({ id: 2, content: '新しいコメント', mine: true }), commentCount: 2 })
    renderSection()
    await screen.findByText('読み込んだ')

    await userEvent.type(input(), '新しいコメント')
    await userEvent.click(submit())

    await vi.waitFor(() => expect(shownContents()).toEqual(['読み込んだ', '新しいコメント']))
    expect(createComment).toHaveBeenCalledWith(10, '新しいコメント')
    expect(input()).toHaveValue('')
    expect(onCountChange).toHaveBeenCalledWith(2)
  })

  test('自分が投稿したコメントは、あとで続きを読み込んで同じものが来ても2つ並ばない', async () => {
    vi.mocked(fetchComments)
      .mockReset()
      .mockResolvedValueOnce(page([aComment({ id: 1, content: '読み込んだ' })], 'c1'))
      .mockResolvedValueOnce(page([aComment({ id: 5, content: '新しいコメント' })]))
    vi.mocked(createComment).mockResolvedValue({ ...aComment({ id: 5, content: '新しいコメント', mine: true }), commentCount: 2 })
    renderSection()
    await screen.findByText('読み込んだ')

    await userEvent.type(input(), '新しいコメント')
    await userEvent.click(submit())
    await screen.findByText('新しいコメント')
    await userEvent.click(screen.getByRole('button', { name: 'さらに表示' }))

    await vi.waitFor(() => expect(fetchComments).toHaveBeenCalledTimes(2))
    expect(shownContents()).toEqual(['読み込んだ', '新しいコメント'])
  })

  test.each([
    [new ApiError(404, 'POST_NOT_FOUND', '文言'), 'この投稿は削除されています'],
    [new ApiError(400, 'VALIDATION_FAILED', '文言', [{ field: 'content', message: 'コメントは1〜280文字で入力してください' }]), 'コメントは1〜280文字で入力してください'],
    [new ApiError(0, 'NETWORK_ERROR', '通信に失敗しました'), '通信に失敗しました'],
  ])('投稿に失敗したら、入力を残したままエラーを出す（%s）', async (error, message) => {
    vi.mocked(createComment).mockRejectedValue(error)
    renderSection()
    await screen.findByText('読み込んだ')

    await userEvent.type(input(), 'コメント')
    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(input()).toHaveValue('コメント')
    expect(submit()).toBeEnabled()
  })

  test('送信中は「送信中…」で押せない', async () => {
    vi.mocked(createComment).mockReturnValue(new Promise(() => {}))
    renderSection()
    await screen.findByText('読み込んだ')

    await userEvent.type(input(), 'コメント')
    await userEvent.click(submit())

    expect(submit()).toHaveTextContent('送信中…')
    expect(submit()).toBeDisabled()
  })

  test('投稿カードの 💬 が押されたら（focusRequest が変わったら）、入力欄にフォーカスを当てる', async () => {
    const { rerender } = renderSection(null)
    await screen.findByText('読み込んだ')
    expect(input()).not.toHaveFocus()

    rerender(<CommentSection postId={10} focusRequest="1" onCountChange={onCountChange} notify={notify} />)

    expect(input()).toHaveFocus()
  })
})

describe('CommentSection：削除（デシジョンテーブル）', () => {
  beforeEach(() => {
    vi.mocked(fetchComments).mockResolvedValue(
      page([
        aComment({ id: 1, content: '自分のコメント', mine: true }),
        aComment({ id: 2, content: '他人のコメント', mine: false, author: aUser({ username: 'carol' }) }),
      ]),
    )
  })

  async function deleteMine() {
    renderSection()
    await screen.findByText('自分のコメント')
    await userEvent.click(screen.getByRole('button', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: '削除する' }))
  }

  test('自分のコメントにだけ「削除」を出す', async () => {
    renderSection()
    await screen.findByText('自分のコメント')

    expect(screen.getAllByRole('button', { name: '削除' })).toHaveLength(1)
  })

  test('確認でキャンセルしたら削除しない', async () => {
    renderSection()
    await screen.findByText('自分のコメント')

    await userEvent.click(screen.getByRole('button', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: 'キャンセル' }))

    expect(deleteComment).not.toHaveBeenCalled()
    expect(shownContents()).toHaveLength(2)
  })

  test('削除できたら一覧から消し、削除後のコメント数を知らせる', async () => {
    vi.mocked(deleteComment).mockResolvedValue({ commentCount: 1 })

    await deleteMine()

    await vi.waitFor(() => expect(shownContents()).toEqual(['他人のコメント']))
    expect(deleteComment).toHaveBeenCalledWith(1)
    expect(onCountChange).toHaveBeenCalledWith(1)
    expect(notify).toHaveBeenCalledWith('コメントを削除しました')
  })

  test('すでに削除されていたら一覧から消してエラーを知らせ、正しいコメント数は取り直す', async () => {
    vi.mocked(deleteComment).mockRejectedValue(new ApiError(404, 'COMMENT_NOT_FOUND', '文言'))
    vi.mocked(fetchPost).mockResolvedValue(aPost({ commentCount: 7 }))

    await deleteMine()

    await vi.waitFor(() => expect(onCountChange).toHaveBeenCalledWith(7))
    expect(shownContents()).toEqual(['他人のコメント'])
    expect(notify).toHaveBeenCalledWith('このコメントはすでに削除されています', true)
  })

  test('件数を取り直せなくても、エラーにはしない', async () => {
    vi.mocked(deleteComment).mockRejectedValue(new ApiError(404, 'COMMENT_NOT_FOUND', '文言'))
    vi.mocked(fetchPost).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '文言'))

    await deleteMine()

    await vi.waitFor(() => expect(fetchPost).toHaveBeenCalled())
    expect(onCountChange).not.toHaveBeenCalled()
  })

  test('それ以外の失敗なら、一覧はそのままでエラーを知らせる', async () => {
    vi.mocked(deleteComment).mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'この操作は実行できません'))

    await deleteMine()

    await vi.waitFor(() => expect(notify).toHaveBeenCalledWith('この操作は実行できません', true))
    expect(shownContents()).toHaveLength(2)
  })
})
