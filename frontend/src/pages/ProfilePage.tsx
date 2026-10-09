import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { fetchProfile, fetchUserPosts, isApiError } from '../api/client'
import type { FollowState, LikeState, Post, Profile } from '../api/types'
import { Avatar } from '../components/Avatar'
import { FollowButton } from '../components/FollowButton'
import { PostCard } from '../components/PostCard'
import { withoutDuplicates } from '../lib/list'
import { useInfiniteScroll } from '../lib/useInfiniteScroll'
import { usePostActions } from '../posts/usePostActions'
import styles from './ProfilePage.module.css'

/**
 * S-07 プロフィール（/users/:username）。ユーザー名が変わったら（ほかの人のプロフィールへ移動したら）画面ごと作り直す。
 * URL のユーザー名は大文字・小文字を区別しない（/users/Yamada でも yamada のページ）
 */
export function ProfileRoute() {
  const { username = '' } = useParams()
  return <ProfilePage key={username.toLowerCase()} username={username} />
}

/** docs/feature-specs/07_profile.md。上にプロフィール、下にそのユーザーの投稿一覧（無限スクロール） */
function ProfilePage({ username }: { username: string }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [profile, setProfile] = useState<Profile | null>(null)
  const [profileStatus, setProfileStatus] = useState<'loading' | 'ready' | 'notFound' | 'error'>('loading')
  const [posts, setPosts] = useState<Post[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [postsStatus, setPostsStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)

  const actions = usePostActions({
    onUpdated: (updated) => setPosts((prev) => prev.map((p) => (p.id === updated.id ? updated : p))),
    onDeleted: (deleted) => setPosts((prev) => prev.filter((p) => p.id !== deleted.id)),
  })
  const { notify } = actions

  // プロフィール編集（S-08）で保存して戻ってきたときのお知らせ
  useEffect(() => {
    const notice = (location.state as { notice?: string } | null)?.notice
    if (notice) {
      notify(notice)
      navigate(location.pathname, { replace: true, state: null })
    }
  }, [location, navigate, notify])

  // プロフィールと投稿一覧は同時に取りに行く（プロフィールを待たずに投稿一覧も読み込む）
  useEffect(() => {
    let cancelled = false
    fetchProfile(username)
      .then((res) => {
        if (cancelled) return
        setProfile(res)
        setProfileStatus('ready')
      })
      .catch((err) => {
        if (!cancelled) setProfileStatus(isApiError(err, 'USER_NOT_FOUND') ? 'notFound' : 'error')
      })
    fetchUserPosts(username)
      .then((res) => {
        if (cancelled) return
        setPosts(res.items)
        setNextCursor(res.nextCursor)
        setPostsStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setPostsStatus('error')
      })
    return () => {
      cancelled = true
    }
  }, [username])

  const loadMore = useCallback(async () => {
    if (!nextCursor) return
    setLoadingMore(true)
    setMoreFailed(false)
    try {
      const res = await fetchUserPosts(username, nextCursor)
      setPosts((prev) => [...prev, ...withoutDuplicates(res.items, prev)])
      setNextCursor(res.nextCursor)
    } catch {
      setMoreFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }, [username, nextCursor])

  const hasNext = nextCursor !== null
  const sentinelRef = useInfiniteScroll<HTMLDivElement>(
    postsStatus === 'ready' && hasNext && !loadingMore && !moreFailed,
    loadMore,
  )

  const handleLikeChange = useCallback(
    (postId: number, state: LikeState) => setPosts((prev) => prev.map((p) => (p.id === postId ? { ...p, ...state } : p))),
    [],
  )
  const handleError = useCallback((message: string) => notify(message, true), [notify])

  function handleFollowChange(state: FollowState) {
    setProfile((prev) => (prev ? { ...prev, followedByMe: state.following, followerCount: state.followerCount } : prev))
  }

  function goBack() {
    if (window.history.state?.idx > 0) navigate(-1)
    else navigate('/')
  }

  return (
    <>
      <div className={styles.header}>
        <button type="button" className={styles.back} aria-label="戻る" onClick={goBack}>
          ←
        </button>
        <h1 className={styles.title}>{profile ? profile.displayName : 'プロフィール'}</h1>
      </div>

      {profileStatus === 'loading' && <p className={styles.message}>読み込み中…</p>}
      {profileStatus === 'notFound' && <p className={styles.message}>このアカウントは存在しません</p>}
      {profileStatus === 'error' && (
        <p className={styles.message}>プロフィールを読み込めませんでした。時間をおいてもう一度お試しください</p>
      )}

      {profileStatus === 'ready' && profile && (
        <>
          <section className={styles.profile} aria-label="プロフィール">
            <div className={styles.top}>
              <Avatar displayName={profile.displayName} iconUrl={profile.iconUrl} size={88} />
              {profile.me ? (
                <Link to="/settings/profile" className={styles.editButton}>
                  プロフィールを編集
                </Link>
              ) : (
                <FollowButton
                  username={profile.username}
                  following={profile.followedByMe}
                  followerCount={profile.followerCount}
                  onChange={handleFollowChange}
                  onError={handleError}
                />
              )}
            </div>
            <p className={styles.displayName}>{profile.displayName}</p>
            <p className={styles.username}>@{profile.username}</p>
            {profile.bio && <p className={styles.bio}>{profile.bio}</p>}
            {/* 押すと S-09 フォロー中・フォロワー一覧へ */}
            <p className={styles.counts}>
              <Link to={`/users/${encodeURIComponent(profile.username)}/following`} className={styles.countLink}>
                <strong>{profile.followingCount}</strong> フォロー中
              </Link>
              <Link to={`/users/${encodeURIComponent(profile.username)}/followers`} className={styles.countLink}>
                <strong>{profile.followerCount}</strong> フォロワー
              </Link>
            </p>
          </section>

          <section aria-label="投稿一覧" aria-busy={postsStatus === 'loading' || loadingMore}>
            {postsStatus === 'loading' && <p className={styles.message}>読み込み中…</p>}
            {postsStatus === 'error' && <p className={styles.message}>投稿を読み込めませんでした</p>}
            {postsStatus === 'ready' && posts.length === 0 && (
              <p className={styles.message}>
                {profile.me ? 'まだ投稿がありません。最初の投稿をしてみましょう' : 'まだ投稿がありません'}
              </p>
            )}
            {posts.map((post) => (
              <PostCard
                key={post.id}
                post={post}
                onEdit={actions.startEdit}
                onDelete={actions.startDelete}
                onLikeChange={handleLikeChange}
                onLikeError={handleError}
              />
            ))}
            {postsStatus === 'ready' && hasNext && (
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
            {postsStatus === 'ready' && !hasNext && posts.length > 0 && (
              <p className={styles.end}>これ以上の投稿はありません</p>
            )}
          </section>
        </>
      )}
      {actions.elements}
    </>
  )
}
