import type { FollowState, UserListItem } from '../api/types'
import { Avatar } from './Avatar'
import { FollowButton } from './FollowButton'
import { UserLink } from './UserLink'
import styles from './UserListRow.module.css'

interface UserListRowProps {
  user: UserListItem
  /** フォロー状態が変わった（押した直後・API の結果・失敗して戻すとき）。一覧の中のこの行を書き換える */
  onFollowChange: (userId: number, state: FollowState) => void
  onError: (message: string) => void
}

/**
 * ユーザー一覧の1行（S-09 フォロー中・フォロワー一覧、S-10 ユーザー検索で共通）。
 * アイコン・表示名・@ユーザー名・自己紹介（1行まで）と、フォローボタン（自分の行には出さない）
 */
export function UserListRow({ user, onFollowChange, onError }: UserListRowProps) {
  return (
    <li className={styles.row} data-user-id={user.id}>
      <UserLink username={user.username} plain className={styles.avatarLink}>
        <Avatar displayName={user.displayName} iconUrl={user.iconUrl} size={44} />
      </UserLink>
      <div className={styles.body}>
        <UserLink username={user.username} className={styles.displayName}>
          {user.displayName}
        </UserLink>
        <UserLink username={user.username} className={styles.username}>
          @{user.username}
        </UserLink>
        {user.bio && <p className={styles.bio}>{user.bio}</p>}
      </div>
      {!user.me && (
        <FollowButton
          username={user.username}
          following={user.followedByMe}
          onChange={(state) => onFollowChange(user.id, state)}
          onError={onError}
        />
      )}
    </li>
  )
}
