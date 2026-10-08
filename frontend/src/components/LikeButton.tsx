import { useRef } from 'react'
import { isApiError, likePost, unlikePost } from '../api/client'
import type { LikeState } from '../api/types'
import styles from './LikeButton.module.css'

interface LikeButtonProps {
  postId: number
  likeCount: number
  likedByMe: boolean
  /** 表示するいいねの状態を変える（押した直後・API の結果・失敗して元に戻すとき） */
  onChange: (postId: number, state: LikeState) => void
  /** 失敗したときのお知らせ */
  onError: (message: string) => void
}

/**
 * 投稿カードの ♥ ボタン（docs/feature-specs/05_like.md）。
 *
 * - 押したらすぐに表示を変え（楽観的更新）、API の結果で上書きする。失敗したら押す前の表示に戻す
 * - 連打対策：送信中は次のリクエストを送らず、最後に押された状態だけ覚えておく。結果が返ってから、
 *   その状態とサーバーの状態が違えばもう一度送る（リクエストの順番が入れ替わって表示と DB がずれないように）
 */
export function LikeButton({ postId, likeCount, likedByMe, onChange, onError }: LikeButtonProps) {
  const sendingRef = useRef(false)
  /** 最後に押された状態（いいね / いいねなし） */
  const wantedRef = useRef(likedByMe)
  /** サーバーで確定している状態。失敗したらここに戻す */
  const confirmedRef = useRef<LikeState>({ likeCount, likedByMe })

  async function send() {
    sendingRef.current = true
    try {
      for (;;) {
        const wanted = wantedRef.current
        const result = await (wanted ? likePost(postId) : unlikePost(postId))
        confirmedRef.current = result
        // 送信中にまた押されて、最後に押された状態が変わっていたら送り直す
        if (wantedRef.current === wanted) {
          onChange(postId, result)
          return
        }
      }
    } catch (err) {
      wantedRef.current = confirmedRef.current.likedByMe
      onChange(postId, confirmedRef.current)
      onError(isApiError(err, 'POST_NOT_FOUND') ? 'この投稿は削除されています' : 'いいねに失敗しました')
    } finally {
      sendingRef.current = false
    }
  }

  function handleClick() {
    if (!sendingRef.current) {
      // 送信していないときに押されたら、今の表示（サーバーの状態）を失敗したときの戻り先にする
      confirmedRef.current = { likeCount, likedByMe }
    }
    const next = !likedByMe
    wantedRef.current = next
    onChange(postId, { likedByMe: next, likeCount: Math.max(0, likeCount + (next ? 1 : -1)) })
    if (!sendingRef.current) void send()
  }

  return (
    <button
      type="button"
      className={`${styles.button} ${likedByMe ? styles.liked : ''}`}
      aria-label={likedByMe ? 'いいねを取り消す' : 'いいね'}
      aria-pressed={likedByMe}
      onClick={handleClick}
    >
      <span className={styles.heart} aria-hidden="true">
        {likedByMe ? '♥' : '♡'}
      </span>
      {/* いいね数が0のときは数字を出さない */}
      {likeCount > 0 && <span className={styles.count}>{likeCount}</span>}
    </button>
  )
}
