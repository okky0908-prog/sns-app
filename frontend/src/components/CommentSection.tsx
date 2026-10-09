import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, createComment, deleteComment, errorMessage, fetchComments, fetchPost, isApiError } from '../api/client'
import type { Comment } from '../api/types'
import { countChars, formatRelativeTime } from '../lib/format'
import { withoutDuplicates } from '../lib/list'
import { Avatar } from './Avatar'
import { ConfirmDialog } from './ConfirmDialog'
import { UserLink } from './UserLink'
import styles from './CommentSection.module.css'

const MAX_LENGTH = 280
const POST_DELETED = 'この投稿は削除されています'

interface CommentSectionProps {
  postId: number
  /**
   * 投稿カードの 💬 が押されたことを表す値（押されるたびに変わる）。変わったらコメント入力欄にカーソルを合わせる。
   * null なら合わせない
   */
  focusRequest: string | null
  /** コメント数が変わった（API が返した値）。投稿カードのコメント数を書き換える */
  onCountChange: (commentCount: number) => void
  /** 画面下のお知らせ */
  notify: (message: string, error?: boolean) => void
}

/**
 * S-06 投稿詳細のコメント欄（docs/feature-specs/04_comment.md）。入力欄と、古い順のコメント一覧（20件ずつ「さらに表示」）。
 * コメントした人のアイコン・表示名・@ユーザー名は、その人のプロフィール（S-07）へのリンク
 */
export function CommentSection({ postId, focusRequest, onCountChange, notify }: CommentSectionProps) {
  /** サーバーから読み込んだコメント（古い順） */
  const [comments, setComments] = useState<Comment[]>([])
  /**
   * この画面で自分が投稿したコメント。読み込んだ一覧とは別に持ち、表示するときに後ろにつなげる。
   * まだ読み込んでいない続きがあるときに一覧の途中に入って古い順が崩れたり、投稿の前に始めた読み込みの結果で消えたりしないようにするため。
   * 続きを読み込んでそちらに含まれたら、こちらは表示しない
   */
  const [posted, setPosted] = useState<Comment[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [loadingMore, setLoadingMore] = useState(false)
  const [content, setContent] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [inputError, setInputError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<Comment | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  // 前後の空白・改行を除いて数える（サーバーと同じ）
  const length = countChars(content.trim())
  const canSubmit = length >= 1 && length <= MAX_LENGTH && !submitting

  const loadFirstPage = useCallback(
    (isCancelled: () => boolean = () => false) =>
      fetchComments(postId)
        .then((res) => {
          if (isCancelled()) return
          setComments(res.items)
          setNextCursor(res.nextCursor)
          setStatus('ready')
        })
        .catch(() => {
          if (!isCancelled()) setStatus('error')
        }),
    [postId],
  )

  useEffect(() => {
    let cancelled = false
    void loadFirstPage(() => cancelled)
    return () => {
      cancelled = true
    }
  }, [loadFirstPage])

  useEffect(() => {
    if (focusRequest !== null) inputRef.current?.focus()
  }, [focusRequest])

  const shown = [...comments, ...withoutDuplicates(posted, comments)]

  function retry() {
    setStatus('loading')
    void loadFirstPage()
  }

  async function loadMore() {
    if (!nextCursor) return
    setLoadingMore(true)
    try {
      const res = await fetchComments(postId, nextCursor)
      setComments((prev) => [...prev, ...withoutDuplicates(res.items, prev)])
      setNextCursor(res.nextCursor)
    } catch (err) {
      notify(isApiError(err, 'POST_NOT_FOUND') ? POST_DELETED : 'コメントを読み込めませんでした', true)
    } finally {
      setLoadingMore(false)
    }
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!canSubmit) return
    setSubmitting(true)
    setInputError(null)
    try {
      const { commentCount, ...created } = await createComment(postId, content)
      setContent('')
      setPosted((prev) => [...prev, created])
      onCountChange(commentCount)
    } catch (err) {
      if (isApiError(err, 'POST_NOT_FOUND')) setInputError(POST_DELETED)
      else if (err instanceof ApiError && err.fieldErrors.length > 0) setInputError(err.fieldErrors[0].message)
      else setInputError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  const cancelDelete = useCallback(() => setDeleting(null), [])

  function removeComment(commentId: number) {
    setComments((prev) => prev.filter((c) => c.id !== commentId))
    setPosted((prev) => prev.filter((c) => c.id !== commentId))
  }

  async function confirmDelete() {
    if (!deleting) return
    const target = deleting
    setDeleting(null)
    try {
      const { commentCount } = await deleteComment(target.id)
      removeComment(target.id)
      onCountChange(commentCount)
      notify('コメントを削除しました')
    } catch (err) {
      if (isApiError(err, 'COMMENT_NOT_FOUND')) {
        // すでに削除されていた。一覧から外し、正しい件数は取り直して反映する
        removeComment(target.id)
        notify('このコメントはすでに削除されています', true)
        fetchPost(postId)
          .then((post) => onCountChange(post.commentCount))
          .catch(() => {}) // 件数を取り直せなくても、次に開いたときに正しい値になる
      } else {
        notify(errorMessage(err), true)
      }
    }
  }

  return (
    <section className={styles.section} aria-label="コメント">
      <form className={styles.form} onSubmit={handleSubmit}>
        <textarea
          ref={inputRef}
          className={styles.textarea}
          rows={3}
          placeholder="コメントを入力"
          aria-label="コメント"
          value={content}
          onChange={(e) => setContent(e.target.value)}
        />
        {inputError && (
          <p className={styles.error} role="alert">
            {inputError}
          </p>
        )}
        <div className={styles.formFooter}>
          <span className={`${styles.counter} ${length > MAX_LENGTH ? styles.over : ''}`} aria-live="polite">
            {length} / {MAX_LENGTH}
          </span>
          <button type="submit" className={styles.submit} disabled={!canSubmit}>
            {submitting ? '送信中…' : 'コメントする'}
          </button>
        </div>
      </form>

      {status === 'loading' && <p className={styles.message}>読み込み中…</p>}
      {status === 'error' && (
        <div className={styles.message}>
          <p>コメントを読み込めませんでした。</p>
          <button type="button" className={styles.more} onClick={retry}>
            再試行
          </button>
        </div>
      )}
      {status === 'ready' && shown.length === 0 && <p className={styles.message}>まだコメントはありません</p>}
      {status === 'ready' && (
        <ul className={styles.list}>
          {shown.map((comment) => (
            <li key={comment.id} className={styles.item} data-comment-id={comment.id}>
              <UserLink username={comment.author.username} plain className={styles.avatarLink}>
                <Avatar displayName={comment.author.displayName} iconUrl={comment.author.iconUrl} size={36} />
              </UserLink>
              <div className={styles.body}>
                <div className={styles.head}>
                  <UserLink username={comment.author.username} className={styles.displayName}>
                    {comment.author.displayName}
                  </UserLink>
                  <UserLink username={comment.author.username} className={styles.meta}>
                    @{comment.author.username}
                  </UserLink>
                  <span className={styles.meta}>·</span>
                  <time
                    className={styles.meta}
                    dateTime={comment.createdAt}
                    title={new Date(comment.createdAt).toLocaleString('ja-JP')}
                  >
                    {formatRelativeTime(comment.createdAt)}
                  </time>
                  {comment.mine && (
                    <button type="button" className={styles.delete} onClick={() => setDeleting(comment)}>
                      削除
                    </button>
                  )}
                </div>
                <p className={styles.content}>{comment.content}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
      {status === 'ready' && nextCursor && (
        <div className={styles.moreArea}>
          <button type="button" className={styles.more} onClick={() => void loadMore()} disabled={loadingMore}>
            {loadingMore ? '読み込み中…' : 'さらに表示'}
          </button>
        </div>
      )}

      {deleting && (
        <ConfirmDialog
          message="このコメントを削除しますか？"
          confirmLabel="削除する"
          onConfirm={() => void confirmDelete()}
          onCancel={cancelDelete}
        />
      )}
    </section>
  )
}
