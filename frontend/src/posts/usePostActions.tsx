import { useCallback, useState } from 'react'
import { ApiError, deletePost, NETWORK_ERROR_MESSAGE, updatePost } from '../api/client'
import type { Post } from '../api/types'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { PostComposer } from '../components/PostComposer'
import { Toast } from '../components/Toast'

interface Options {
  onUpdated: (post: Post) => void
  onDeleted: (post: Post) => void
}

/**
 * 投稿の編集（S-05）・削除の流れをまとめたもの。タイムラインと投稿詳細で共通。
 * 編集モーダル・削除の確認ダイアログ・お知らせを elements として返すので、画面の中に置く。
 */
export function usePostActions({ onUpdated, onDeleted }: Options) {
  const [editing, setEditing] = useState<Post | null>(null)
  const [deleting, setDeleting] = useState<Post | null>(null)
  const [toast, setToast] = useState<{ id: number; message: string; error: boolean } | null>(null)

  const notify = useCallback(
    (message: string, error = false) => setToast((prev) => ({ id: (prev?.id ?? 0) + 1, message, error })),
    [],
  )
  const clearToast = useCallback(() => setToast(null), [])
  const closeEditor = useCallback(() => setEditing(null), [])
  const cancelDelete = useCallback(() => setDeleting(null), [])

  async function submitEdit(content: string) {
    if (!editing) return
    try {
      const updated = await updatePost(editing.id, content)
      setEditing(null)
      onUpdated(updated)
      notify('投稿を更新しました')
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        // 編集中に削除されていた
        setEditing(null)
        onDeleted(editing)
        notify('この投稿はすでに削除されています', true)
        return
      }
      throw err // それ以外はモーダルの中にエラーを出す
    }
  }

  async function confirmDelete() {
    if (!deleting) return
    const post = deleting
    setDeleting(null)
    try {
      await deletePost(post.id)
      onDeleted(post)
      notify('投稿を削除しました')
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        onDeleted(post)
        notify('この投稿はすでに削除されています', true)
      } else {
        notify(err instanceof ApiError && err.status !== 0 ? err.message : NETWORK_ERROR_MESSAGE, true)
      }
    }
  }

  const elements = (
    <>
      {editing && <PostComposer mode="edit" initialContent={editing.content} onSubmit={submitEdit} onClose={closeEditor} />}
      {deleting && (
        <ConfirmDialog
          message={'この投稿を削除しますか？\nこの操作は取り消せません'}
          confirmLabel="削除する"
          onConfirm={confirmDelete}
          onCancel={cancelDelete}
        />
      )}
      {toast && <Toast key={toast.id} message={toast.message} error={toast.error} onDone={clearToast} />}
    </>
  )

  return { startEdit: setEditing, startDelete: setDeleting, notify, elements }
}
