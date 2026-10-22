// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, deletePost, updatePost } from '../api/client'
import type { Post } from '../api/types'
import { aPost } from '../test/helpers'
import { usePostActions } from './usePostActions'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  updatePost: vi.fn(),
  deletePost: vi.fn(),
}))

const onUpdated = vi.fn()
const onDeleted = vi.fn()

beforeEach(() => {
  vi.mocked(updatePost).mockReset()
  vi.mocked(deletePost).mockReset()
  onUpdated.mockReset()
  onDeleted.mockReset()
})

const post = aPost({ id: 10, content: 'もとの本文', mine: true })

/** 画面の代わり：編集・削除を始めるボタンと、フックが返す表示（モーダル・確認・お知らせ）を置く */
function Harness({ target }: { target: Post }) {
  const actions = usePostActions({ onUpdated, onDeleted })
  return (
    <>
      <button type="button" onClick={() => actions.startEdit(target)}>
        編集を始める
      </button>
      <button type="button" onClick={() => actions.startDelete(target)}>
        削除を始める
      </button>
      {actions.elements}
    </>
  )
}

const toast = () => screen.queryByRole('status')

describe('usePostActions：編集（デシジョンテーブル：保存の結果）', () => {
  async function edit(newContent: string) {
    render(<Harness target={post} />)
    await userEvent.click(screen.getByRole('button', { name: '編集を始める' }))
    const textbox = screen.getByRole('textbox', { name: '本文' })
    expect(textbox).toHaveValue('もとの本文') // 今の本文を入れて開く
    await userEvent.clear(textbox)
    await userEvent.type(textbox, newContent)
    await userEvent.click(screen.getByRole('button', { name: '保存する' }))
  }

  test('保存できた → モーダルを閉じ、更新後の投稿を知らせ、「投稿を更新しました」', async () => {
    const updated = { ...post, content: '新しい本文' }
    vi.mocked(updatePost).mockResolvedValue(updated)

    await edit('新しい本文')

    await vi.waitFor(() => expect(onUpdated).toHaveBeenCalledWith(updated))
    expect(updatePost).toHaveBeenCalledWith(10, '新しい本文')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(toast()).toHaveTextContent('投稿を更新しました')
  })

  test('編集中に削除されていた → モーダルを閉じ、「すでに削除されていた」として知らせる', async () => {
    vi.mocked(updatePost).mockRejectedValue(new ApiError(404, 'POST_NOT_FOUND', '文言'))

    await edit('新しい本文')

    await vi.waitFor(() => expect(onDeleted).toHaveBeenCalledWith(post, true))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(toast()).toHaveTextContent('この投稿はすでに削除されています')
    expect(onUpdated).not.toHaveBeenCalled()
  })

  test('それ以外の失敗 → モーダルを開いたまま、中にエラーを出す', async () => {
    vi.mocked(updatePost).mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'サーバーでエラーが発生しました'))

    await edit('新しい本文')

    expect(await screen.findByText('サーバーでエラーが発生しました')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '投稿を編集' })).toBeInTheDocument()
    expect(onUpdated).not.toHaveBeenCalled()
    expect(onDeleted).not.toHaveBeenCalled()
  })

  test('変えずに閉じたら、何もしない', async () => {
    render(<Harness target={post} />)
    await userEvent.click(screen.getByRole('button', { name: '編集を始める' }))

    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(updatePost).not.toHaveBeenCalled()
  })
})

describe('usePostActions：削除（デシジョンテーブル：確認 × 削除の結果）', () => {
  async function startDelete() {
    render(<Harness target={post} />)
    await userEvent.click(screen.getByRole('button', { name: '削除を始める' }))
  }

  test('確認で「キャンセル」→ 削除しない', async () => {
    await startDelete()
    expect(screen.getByRole('alertdialog')).toHaveTextContent('この投稿を削除しますか？')

    await userEvent.click(screen.getByRole('button', { name: 'キャンセル' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(deletePost).not.toHaveBeenCalled()
  })

  test('確認で「削除する」→ 削除できた → 確認を閉じ、消えたことを知らせ、「投稿を削除しました」', async () => {
    vi.mocked(deletePost).mockResolvedValue()
    await startDelete()

    await userEvent.click(screen.getByRole('button', { name: '削除する' }))

    await vi.waitFor(() => expect(onDeleted).toHaveBeenCalledWith(post, false))
    expect(deletePost).toHaveBeenCalledWith(10)
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(toast()).toHaveTextContent('投稿を削除しました')
  })

  test('確認で「削除する」→ すでに削除されていた → 「すでに削除されていた」として知らせる', async () => {
    vi.mocked(deletePost).mockRejectedValue(new ApiError(404, 'POST_NOT_FOUND', '文言'))
    await startDelete()

    await userEvent.click(screen.getByRole('button', { name: '削除する' }))

    await vi.waitFor(() => expect(onDeleted).toHaveBeenCalledWith(post, true))
    expect(toast()).toHaveTextContent('この投稿はすでに削除されています')
  })

  test('確認で「削除する」→ それ以外の失敗 → 一覧はそのままで、エラーの文言を知らせる', async () => {
    vi.mocked(deletePost).mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'この操作は実行できません'))
    await startDelete()

    await userEvent.click(screen.getByRole('button', { name: '削除する' }))

    await vi.waitFor(() => expect(toast()).toHaveTextContent('この操作は実行できません'))
    expect(onDeleted).not.toHaveBeenCalled()
  })
})
