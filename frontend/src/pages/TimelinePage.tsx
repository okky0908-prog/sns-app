import { useCallback, useEffect, useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router'
import { createPost, fetchTimeline, type TimelineTab } from '../api/client'
import type { Post } from '../api/types'
import { PostCard } from '../components/PostCard'
import { PostComposer } from '../components/PostComposer'
import { usePostActions } from '../posts/usePostActions'
import { useTimelineEvents } from '../timeline/timelineStreamContext'
import styles from './TimelinePage.module.css'

/** この位置より上を見ているときは、新しい投稿をすぐ先頭に入れる（それより下を読んでいるときは「新しい投稿」のボタンにためる） */
const NEAR_TOP_PX = 80
/** 一番下の目印がここまで近づいたら続きを読み込む（下まで行き着く前に読み込み始める） */
const PRELOAD_MARGIN = '400px'

/** すでにある投稿と同じ ID のものを除く */
function withoutDuplicates(posts: Post[], existing: Post[]): Post[] {
  const ids = new Set(existing.map((p) => p.id))
  return posts.filter((p) => !ids.has(p.id))
}

/** S-03 タイムライン（docs/screens.md・docs/feature-specs/03_timeline.md） */
export function TimelinePage({ tab }: { tab: TimelineTab }) {
  const location = useLocation()
  const navigate = useNavigate()
  const [posts, setPosts] = useState<Post[]>([])
  /** 下の方を読んでいる間に届いた新しい投稿（「↑ N件の新しい投稿」を押すと先頭に入る） */
  const [pending, setPending] = useState<Post[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [hasNext, setHasNext] = useState(false)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const [composing, setComposing] = useState(false)
  const sentinelRef = useRef<HTMLDivElement>(null)
  // 通知を受けたときに「すでに表示している投稿」を読むため（状態の書き換えの中で別の状態を書き換えないように）
  const postsRef = useRef(posts)
  useEffect(() => {
    postsRef.current = posts
  }, [posts])

  const actions = usePostActions({
    onUpdated: (updated) => setPosts((prev) => prev.map((p) => (p.id === updated.id ? updated : p))),
    onDeleted: (deleted) => setPosts((prev) => prev.filter((p) => p.id !== deleted.id)),
  })
  const { notify } = actions

  // 投稿詳細で削除して戻ってきたときのお知らせ
  useEffect(() => {
    const notice = (location.state as { notice?: string } | null)?.notice
    if (notice) {
      notify(notice)
      navigate(location.pathname, { replace: true, state: null })
    }
  }, [location, navigate, notify])

  // 1ページ目を読み込む（タブを切り替えると router の key で画面ごと作り直されるので、ここは最初の1回だけ）
  const loadFirstPage = useCallback(
    (isCancelled: () => boolean = () => false) =>
      fetchTimeline(tab)
        .then((res) => {
          if (isCancelled()) return
          setPosts(res.items)
          setNextCursor(res.nextCursor)
          setHasNext(res.hasNext)
          setStatus('ready')
        })
        .catch(() => {
          if (!isCancelled()) setStatus('error')
        }),
    [tab],
  )

  useEffect(() => {
    let cancelled = false
    void loadFirstPage(() => cancelled)
    return () => {
      cancelled = true
    }
  }, [loadFirstPage])

  function retry() {
    setStatus('loading')
    void loadFirstPage()
  }

  // ===== 無限スクロール：一番下の目印が近づいたら続きを読み込む =====
  const loadMore = useCallback(async () => {
    if (!nextCursor) return
    setLoadingMore(true)
    setMoreFailed(false)
    try {
      const res = await fetchTimeline(tab, nextCursor)
      setPosts((prev) => [...prev, ...withoutDuplicates(res.items, prev)])
      setNextCursor(res.nextCursor)
      setHasNext(res.hasNext)
    } catch {
      setMoreFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }, [tab, nextCursor])

  const canLoadMore = status === 'ready' && hasNext && !loadingMore && !moreFailed
  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel || !canLoadMore) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) void loadMore()
      },
      { rootMargin: PRELOAD_MARGIN },
    )
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [canLoadMore, loadMore])

  // ===== リアルタイム反映 =====

  /** 届いた新しい投稿を、一番上を見ていればすぐ先頭に、下の方を読んでいれば「新しい投稿」にためる */
  const receiveNewPosts = useCallback((incoming: Post[]) => {
    if (incoming.length === 0) return
    if (window.scrollY <= NEAR_TOP_PX) {
      setPosts((prev) => [...withoutDuplicates(incoming, prev), ...prev])
    } else {
      setPending((queued) => [...withoutDuplicates(incoming, [...postsRef.current, ...queued]), ...queued])
    }
  }, [])

  useTimelineEvents((event) => {
    switch (event.type) {
      case 'post-created':
        // フォロー中タブには、自分とフォロー中の人の投稿だけを出す
        if (tab === 'all' || event.inFollowing) receiveNewPosts([event.post])
        break
      case 'post-updated': {
        const replace = (list: Post[]) => list.map((p) => (p.id === event.post.id ? event.post : p))
        setPosts(replace)
        setPending(replace)
        break
      }
      case 'post-deleted': {
        const remove = (list: Post[]) => list.filter((p) => p.id !== event.postId)
        setPosts(remove)
        setPending(remove)
        break
      }
      case 'reconnected':
        // 接続が切れていた間に増えた投稿を取りこぼさないよう、最新の1ページを取り直す
        if (status === 'ready') {
          fetchTimeline(tab)
            .then((res) => receiveNewPosts(res.items))
            .catch(() => {})
        }
        break
    }
  })

  function showPending() {
    setPosts((prev) => [...withoutDuplicates(pending, prev), ...prev])
    setPending([])
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  async function handleCreate(content: string) {
    const created = await createPost(content)
    setComposing(false)
    // 自分の投稿はどちらのタブにも出るので、先頭に足す（通知でも届くが、同じ ID は二重に入れない）
    setPending((queued) => queued.filter((p) => p.id !== created.id))
    setPosts((prev) => [created, ...prev.filter((p) => p.id !== created.id)])
    window.scrollTo(0, 0)
    notify('投稿しました')
  }

  return (
    <>
      <div className={styles.header}>
        <h1 className={styles.title}>ホーム</h1>
        <button type="button" className={styles.compose} onClick={() => setComposing(true)}>
          ✏ 投稿する
        </button>
      </div>
      <nav className={styles.tabs} aria-label="タイムラインの切り替え">
        <NavLink to="/" end className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}>
          フォロー中
        </NavLink>
        <NavLink to="/all" className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}>
          全体
        </NavLink>
      </nav>

      {pending.length > 0 && (
        <div className={styles.newPostsArea}>
          <button type="button" className={styles.newPosts} onClick={showPending}>
            ↑ {pending.length}件の新しい投稿
          </button>
        </div>
      )}

      <section aria-label="投稿一覧" aria-busy={status === 'loading' || loadingMore}>
        {status === 'loading' && <p className={styles.message}>読み込み中…</p>}
        {status === 'error' && (
          <div className={styles.message}>
            <p>タイムラインを読み込めませんでした。</p>
            <button type="button" className={styles.more} onClick={retry}>
              再試行
            </button>
          </div>
        )}
        {status === 'ready' && posts.length === 0 && (
          <p className={styles.message}>
            {tab === 'all'
              ? 'まだ誰も投稿していません。最初の投稿をしてみましょう'
              : 'まだ投稿がありません。「全体」タブで気になる人を探してみましょう（フォロー機能は今後追加します）'}
          </p>
        )}
        {posts.map((post) => (
          <PostCard key={post.id} post={post} onEdit={actions.startEdit} onDelete={actions.startDelete} />
        ))}

        {status === 'ready' && hasNext && (
          <div ref={sentinelRef} className={styles.moreArea} data-testid="timeline-sentinel">
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
        {status === 'ready' && !hasNext && posts.length > 0 && (
          <p className={styles.end}>これ以上の投稿はありません</p>
        )}
      </section>

      {composing && <PostComposer mode="create" onSubmit={handleCreate} onClose={() => setComposing(false)} />}
      {actions.elements}
    </>
  )
}
