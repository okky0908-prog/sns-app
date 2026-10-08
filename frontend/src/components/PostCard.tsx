import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import type { LikeState, Post } from '../api/types'
import { formatRelativeTime } from '../lib/format'
import { LikeButton } from './LikeButton'
import styles from './PostCard.module.css'

interface PostCardProps {
  post: Post
  /** 投稿詳細（S-06）で使うときは true。カードのクリックで移動しない */
  detail?: boolean
  onEdit: (post: Post) => void
  onDelete: (post: Post) => void
  /** いいねの状態が変わった（押した直後・API の結果・失敗して元に戻すとき）。画面が持っている投稿を差し替える */
  onLikeChange: (postId: number, state: LikeState) => void
  /** いいねに失敗したときのお知らせ */
  onLikeError: (message: string) => void
}

/**
 * 投稿カード（docs/screens.md「投稿カード」）。タイムラインと投稿詳細で共通。
 * インプレッション数・リツイートは置かない（差別化）
 */
export function PostCard({ post, detail = false, onEdit, onDelete, onLikeChange, onLikeError }: PostCardProps) {
  const navigate = useNavigate()
  const path = `/posts/${post.id}`

  // カードの余白をクリックしたら投稿詳細へ（キーボードでは日時のリンクから移動できる）
  function handleCardClick(e: MouseEvent<HTMLElement>) {
    if (detail || (e.target as HTMLElement).closest('a, button')) return
    navigate(path)
  }

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions -- マウス操作の補助。キーボードでは日時のリンクを使う
    <article className={`${styles.card} ${detail ? styles.detail : ''}`} data-post-id={post.id} onClick={handleCardClick}>
      <div className={styles.avatar} aria-hidden="true">
        {[...post.author.displayName][0] ?? '?'}
      </div>
      <div className={styles.body}>
        <div className={styles.head}>
          <span className={styles.displayName}>{post.author.displayName}</span>
          <span className={styles.meta}>@{post.author.username}</span>
          <span className={styles.meta}>·</span>
          <Link to={path} className={styles.time} title={new Date(post.createdAt).toLocaleString('ja-JP')}>
            <time dateTime={post.createdAt}>{formatRelativeTime(post.createdAt)}</time>
          </Link>
          {post.editedAt && <span className={styles.meta}>· 編集済み</span>}
          {post.mine && <PostMenu onEdit={() => onEdit(post)} onDelete={() => onDelete(post)} />}
        </div>
        <p className={styles.content}>{post.content}</p>
        <div className={styles.actions}>
          {/* 投稿詳細を開いてコメント入力欄にカーソルを合わせる。詳細の中で押したときは履歴を増やさない */}
          <Link
            to={path}
            state={{ focusComment: true }}
            replace={detail}
            className={styles.commentLink}
            aria-label={`コメント（${post.commentCount}件）`}
          >
            <span className={styles.commentIcon} aria-hidden="true">
              💬
            </span>
            {/* コメント数が0のときは数字を出さない */}
            {post.commentCount > 0 && <span>{post.commentCount}</span>}
          </Link>
          <LikeButton
            postId={post.id}
            likeCount={post.likeCount}
            likedByMe={post.likedByMe}
            onChange={onLikeChange}
            onError={onLikeError}
          />
        </div>
      </div>
    </article>
  )
}

/** 自分の投稿にだけ出す「…」メニュー（編集・削除） */
function PostMenu({ onEdit, onDelete }: { onEdit: () => void; onDelete: () => void }) {
  const [open, setOpen] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    // メニューの外をクリックしたら閉じる
    const onDocumentClick = (e: globalThis.MouseEvent) => {
      if (!wrapperRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('click', onDocumentClick)
    return () => document.removeEventListener('click', onDocumentClick)
  }, [open])

  return (
    <div className={styles.menuWrapper} ref={wrapperRef}>
      <button
        type="button"
        className={styles.menuButton}
        aria-label="メニュー"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        …
      </button>
      {open && (
        <div className={styles.menu} role="menu">
          <button type="button" role="menuitem" onClick={() => { setOpen(false); onEdit() }}>
            編集
          </button>
          <button type="button" role="menuitem" className={styles.danger} onClick={() => { setOpen(false); onDelete() }}>
            削除
          </button>
        </div>
      )}
    </div>
  )
}
