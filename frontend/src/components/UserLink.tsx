import type { ReactNode } from 'react'
import { Link } from 'react-router'
import styles from './UserLink.module.css'

interface UserLinkProps {
  username: string
  children: ReactNode
  className?: string
  /** アイコンのように、文字に下線を出さないもの */
  plain?: boolean
}

/**
 * ユーザーへのリンク（docs/screens.md「ユーザーへのリンク（共通）」）。アイコン・表示名・@ユーザー名に使い、S-07 プロフィールへ移動する。
 * 投稿カードの中に置いても、カードのクリック（投稿詳細への移動）は起きない（PostCard がリンクのクリックを除外している）
 */
export function UserLink({ username, children, className = '', plain = false }: UserLinkProps) {
  return (
    <Link to={`/users/${encodeURIComponent(username)}`} className={`${plain ? styles.plain : styles.link} ${className}`}>
      {children}
    </Link>
  )
}
