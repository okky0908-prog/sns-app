import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { fetchPost, isApiError } from '../api/client'
import type { LikeState, Post } from '../api/types'
import { PostCard } from '../components/PostCard'
import { usePostActions } from '../posts/usePostActions'
import styles from './PostDetailPage.module.css'

/** S-06 投稿詳細（docs/screens.md）。コメント（F-30〜F-32）は、コメント機能の実装時に追加する */
export function PostDetailPage() {
  const { postId } = useParams()
  const navigate = useNavigate()
  const id = Number(postId)
  const validId = Number.isInteger(id) && id > 0
  // 読み込んだ結果を、どの投稿 ID の結果かと一緒に持つ（URL が変わったら「読み込み中」に戻る）
  const [result, setResult] = useState<{ id: number; post: Post | null; status: 'ready' | 'notFound' | 'error' } | null>(
    null,
  )
  const status = !validId ? 'notFound' : result?.id === id ? result.status : 'loading'
  const post = result?.id === id ? result.post : null

  const actions = usePostActions({
    onUpdated: (updated) => setResult({ id: updated.id, post: updated, status: 'ready' }),
    // 削除したらタイムラインに戻る（お知らせはタイムライン側で出す）
    onDeleted: () => navigate('/', { replace: true, state: { notice: '投稿を削除しました' } }),
  })

  function handleLikeChange(likedPostId: number, state: LikeState) {
    setResult((prev) => (prev?.post?.id === likedPostId ? { ...prev, post: { ...prev.post, ...state } } : prev))
  }

  useEffect(() => {
    if (!validId) return
    let cancelled = false
    fetchPost(id)
      .then((res) => {
        if (!cancelled) setResult({ id, post: res, status: 'ready' })
      })
      .catch((err) => {
        if (!cancelled)
          setResult({ id, post: null, status: isApiError(err, 'POST_NOT_FOUND') ? 'notFound' : 'error' })
      })
    return () => {
      cancelled = true
    }
  }, [id, validId])

  function goBack() {
    // アプリ内から来たときは前の画面へ、URL を直接開いたときはタイムラインへ
    if (window.history.state?.idx > 0) navigate(-1)
    else navigate('/')
  }

  return (
    <>
      <div className={styles.header}>
        <button type="button" className={styles.back} aria-label="戻る" onClick={goBack}>
          ←
        </button>
        <h1 className={styles.title}>投稿</h1>
      </div>
      {status === 'loading' && <p className={styles.message}>読み込み中…</p>}
      {status === 'notFound' && <p className={styles.message}>この投稿は見つかりません</p>}
      {status === 'error' && <p className={styles.message}>投稿を読み込めませんでした。時間をおいてもう一度お試しください</p>}
      {status === 'ready' && post && (
        <>
          <PostCard
            post={post}
            detail
            onEdit={actions.startEdit}
            onDelete={actions.startDelete}
            onLikeChange={handleLikeChange}
            onLikeError={(message) => actions.notify(message, true)}
          />
          <p className={styles.note}>コメント機能は今後追加します</p>
        </>
      )}
      {actions.elements}
    </>
  )
}
