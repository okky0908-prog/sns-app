import { useCallback, useEffect, useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router'
import { fetchFollowList, isApiError, type FollowListKind } from '../api/client'
import type { FollowState, UserListItem } from '../api/types'
import { Toast } from '../components/Toast'
import { UserListRow } from '../components/UserListRow'
import { withoutDuplicates } from '../lib/list'
import { useInfiniteScroll } from '../lib/useInfiniteScroll'
import styles from './FollowListPage.module.css'

const EMPTY_MESSAGE: Record<FollowListKind, string> = {
  following: 'まだ誰もフォローしていません',
  followers: 'まだフォロワーはいません',
}

/** S-09 のルート。ユーザー名かタブ（フォロー中・フォロワー）が変わったら画面ごと作り直して、1ページ目から読み込む */
export function FollowListRoute({ kind }: { kind: FollowListKind }) {
  const { username = '' } = useParams()
  return <FollowListPage key={`${username.toLowerCase()}/${kind}`} username={username} kind={kind} />
}

/** S-09 フォロー中・フォロワー一覧（docs/feature-specs/06_follow.md）。20件ずつ無限スクロール */
function FollowListPage({ username, kind }: { username: string; kind: FollowListKind }) {
  const navigate = useNavigate()
  const [users, setUsers] = useState<UserListItem[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'notFound' | 'error'>('loading')
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const [toast, setToast] = useState<{ id: number; message: string } | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchFollowList(username, kind)
      .then((res) => {
        if (cancelled) return
        setUsers(res.items)
        setNextCursor(res.nextCursor)
        setStatus('ready')
      })
      .catch((err) => {
        if (!cancelled) setStatus(isApiError(err, 'USER_NOT_FOUND') ? 'notFound' : 'error')
      })
    return () => {
      cancelled = true
    }
  }, [username, kind])

  const loadMore = useCallback(async () => {
    if (!nextCursor) return
    setLoadingMore(true)
    setMoreFailed(false)
    try {
      const res = await fetchFollowList(username, kind, nextCursor)
      setUsers((prev) => [...prev, ...withoutDuplicates(res.items, prev)])
      setNextCursor(res.nextCursor)
    } catch {
      setMoreFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }, [username, kind, nextCursor])

  const hasNext = nextCursor !== null
  const sentinelRef = useInfiniteScroll<HTMLDivElement>(status === 'ready' && hasNext && !loadingMore && !moreFailed, loadMore)

  // フォローボタンの状態は「ログイン中の自分がその人をフォローしているか」なので、その行の followedByMe だけを書き換える
  const handleFollowChange = useCallback(
    (userId: number, state: FollowState) =>
      setUsers((prev) => prev.map((u) => (u.id === userId ? { ...u, followedByMe: state.following } : u))),
    [],
  )
  const handleError = useCallback((message: string) => setToast((prev) => ({ id: (prev?.id ?? 0) + 1, message })), [])
  const clearToast = useCallback(() => setToast(null), [])

  function goBack() {
    if (window.history.state?.idx > 0) navigate(-1)
    else navigate(`/users/${encodeURIComponent(username)}`)
  }

  const base = `/users/${encodeURIComponent(username)}`
  return (
    <>
      <div className={styles.header}>
        <button type="button" className={styles.back} aria-label="戻る" onClick={goBack}>
          ←
        </button>
        <h1 className={styles.title}>@{username}</h1>
      </div>
      <nav className={styles.tabs} aria-label="一覧の切り替え">
        {/* replace：タブの切り替えで履歴を増やさない（「戻る」でプロフィールへ戻れるように） */}
        <NavLink to={`${base}/following`} replace className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}>
          フォロー中
        </NavLink>
        <NavLink to={`${base}/followers`} replace className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}>
          フォロワー
        </NavLink>
      </nav>

      <section aria-label={kind === 'following' ? 'フォロー中' : 'フォロワー'} aria-busy={status === 'loading' || loadingMore}>
        {status === 'loading' && <p className={styles.message}>読み込み中…</p>}
        {status === 'notFound' && <p className={styles.message}>このアカウントは存在しません</p>}
        {status === 'error' && <p className={styles.message}>一覧を読み込めませんでした。時間をおいてもう一度お試しください</p>}
        {status === 'ready' && users.length === 0 && <p className={styles.message}>{EMPTY_MESSAGE[kind]}</p>}
        {status === 'ready' && users.length > 0 && (
          <ul className={styles.list}>
            {users.map((user) => (
              <UserListRow key={user.id} user={user} onFollowChange={handleFollowChange} onError={handleError} />
            ))}
          </ul>
        )}
        {status === 'ready' && hasNext && (
          <div ref={sentinelRef} className={styles.moreArea}>
            {moreFailed ? (
              <>
                <p className={styles.error}>読み込みに失敗しました</p>
                <button type="button" className={styles.more} onClick={() => void loadMore()}>
                  再試行
                </button>
              </>
            ) : (
              <p className={styles.loadingMore}>読み込み中…</p>
            )}
          </div>
        )}
      </section>
      {toast && <Toast key={toast.id} message={toast.message} error onDone={clearToast} />}
    </>
  )
}
