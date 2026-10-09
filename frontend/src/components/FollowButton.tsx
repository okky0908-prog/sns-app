import { useState } from 'react'
import { followUser, isApiError, unfollowUser } from '../api/client'
import type { FollowState } from '../api/types'
import styles from './FollowButton.module.css'

interface FollowButtonProps {
  username: string
  /** ログイン中の自分がフォローしているか */
  following: boolean
  /** 相手のフォロワー数（押した直後に±1して見せる） */
  followerCount: number
  /** フォロー状態が変わった（押した直後・API の結果・失敗して元に戻すとき） */
  onChange: (state: FollowState) => void
  /** 失敗したときのお知らせ */
  onError: (message: string) => void
}

/**
 * フォローボタン（docs/feature-specs/06_follow.md）。S-07 プロフィール・S-09 一覧・S-10 検索で共通。自分自身には出さない（呼ぶ側で判定する）。
 *
 * - 押したらすぐ表示を切り替え、API の結果で上書きする。失敗したら押す前に戻す
 * - 送信中は押せない（連打で状態がずれないように）。フォロー解除の確認ダイアログは出さない
 * - フォロー中のボタンは、マウスを乗せると赤字の「フォロー解除」に変わる
 */
export function FollowButton({ username, following, followerCount, onChange, onError }: FollowButtonProps) {
  const [sending, setSending] = useState(false)

  async function handleClick() {
    const before = { following, followerCount }
    const next = !following
    onChange({ following: next, followerCount: Math.max(0, followerCount + (next ? 1 : -1)) })
    setSending(true)
    try {
      onChange(await (next ? followUser(username) : unfollowUser(username)))
    } catch (err) {
      onChange(before)
      onError(isApiError(err, 'USER_NOT_FOUND') ? 'このユーザーは見つかりません' : 'フォローの変更に失敗しました')
    } finally {
      setSending(false)
    }
  }

  return (
    <button
      type="button"
      className={`${styles.button} ${following ? styles.following : styles.notFollowing}`}
      aria-pressed={following}
      disabled={sending}
      onClick={() => void handleClick()}
    >
      {following ? (
        <>
          <span className={styles.label}>フォロー中</span>
          <span className={styles.hoverLabel}>フォロー解除</span>
        </>
      ) : (
        'フォローする'
      )}
    </button>
  )
}
