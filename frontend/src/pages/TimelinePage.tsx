import { type MouseEvent, useCallback, useEffect, useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router'
import { createPost, fetchNewPostCount, fetchTimeline, type TimelineTab } from '../api/client'
import type { LikeState, Post } from '../api/types'
import { NewPostsDialog } from '../components/NewPostsDialog'
import { PostCard } from '../components/PostCard'
import { PostComposer } from '../components/PostComposer'
import { withoutDuplicates } from '../lib/list'
import { useInfiniteScroll } from '../lib/useInfiniteScroll'
import { usePostActions } from '../posts/usePostActions'
import styles from './TimelinePage.module.css'

/** 新しい投稿があるかを確認する間隔（docs/feature-specs/03_timeline.md） */
const NEW_POSTS_CHECK_INTERVAL_MS = 60_000

/** S-03 タイムライン（docs/screens.md・docs/feature-specs/03_timeline.md） */
export function TimelinePage({ tab }: { tab: TimelineTab }) {
  const location = useLocation()
  const navigate = useNavigate()
  const [posts, setPosts] = useState<Post[]>([])
  /** 前回取り直してから増えた、他人の投稿の件数（「↑ N件の新しい投稿」） */
  const [newCount, setNewCount] = useState(0)
  /** モーダルを「あとで」で閉じたときの件数。これより増えたら、またモーダルで知らせる */
  const [dismissedCount, setDismissedCount] = useState(0)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [hasNext, setHasNext] = useState(false)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const [composing, setComposing] = useState(false)
  /** 最後にサーバーから取った一番新しい投稿の ID。新しい投稿の件数はこれより後を数える（自分の投稿は数えない） */
  const newestIdRef = useRef(0)
  /** 1ページ目を取り直すたびに増やす。取り直す前に始めた「続きの読み込み」「件数の確認」の結果は捨てる */
  const generationRef = useRef(0)

  const actions = usePostActions({
    onUpdated: (updated) => setPosts((prev) => prev.map((p) => (p.id === updated.id ? updated : p))),
    onDeleted: (deleted) => setPosts((prev) => prev.filter((p) => p.id !== deleted.id)),
  })
  const { notify } = actions

  // いいね：押した直後・API の結果・失敗して戻すときに、一覧の中のその投稿だけを書き換える
  const handleLikeChange = useCallback(
    (postId: number, state: LikeState) => setPosts((prev) => prev.map((p) => (p.id === postId ? { ...p, ...state } : p))),
    [],
  )
  const handleLikeError = useCallback((message: string) => notify(message, true), [notify])

  // 投稿詳細で削除して戻ってきたときのお知らせ
  useEffect(() => {
    const notice = (location.state as { notice?: string } | null)?.notice
    if (notice) {
      notify(notice)
      navigate(location.pathname, { replace: true, state: null })
    }
  }, [location, navigate, notify])

  // 1ページ目を読み込む（タブを切り替えると router の key で画面ごと作り直される。同じタブでは「最新を取り直す」ときにも使う）
  const loadFirstPage = useCallback(
    (isCancelled: () => boolean = () => false) =>
      fetchTimeline(tab)
        .then((res) => {
          if (isCancelled()) return
          generationRef.current += 1
          setPosts(res.items)
          newestIdRef.current = res.items[0]?.id ?? 0
          setNewCount(0)
          setDismissedCount(0)
          setNextCursor(res.nextCursor)
          setHasNext(res.hasNext)
          setMoreFailed(false)
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
    const generation = generationRef.current
    setLoadingMore(true)
    setMoreFailed(false)
    try {
      const res = await fetchTimeline(tab, nextCursor)
      if (generation !== generationRef.current) return
      setPosts((prev) => [...prev, ...withoutDuplicates(res.items, prev)])
      setNextCursor(res.nextCursor)
      setHasNext(res.hasNext)
    } catch {
      if (generation === generationRef.current) setMoreFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }, [tab, nextCursor])

  const canLoadMore = status === 'ready' && hasNext && !loadingMore && !moreFailed
  const sentinelRef = useInfiniteScroll<HTMLDivElement>(canLoadMore, loadMore)

  // ===== 新しい投稿のお知らせ：一定時間ごとに件数だけ確認し、押されたときだけ最新を取り直す =====

  useEffect(() => {
    if (status !== 'ready') return
    let cancelled = false
    const check = () => {
      // 裏に回っているブラウザのタブでは確認しない（表に戻ったときにすぐ確認する）
      if (document.visibilityState !== 'visible') return
      const generation = generationRef.current
      fetchNewPostCount(tab, newestIdRef.current)
        .then((count) => {
          if (!cancelled && generation === generationRef.current) setNewCount(count)
        })
        .catch(() => {}) // 確認に失敗しても画面には出さない（次の確認でまた試す）
    }
    const timer = setInterval(check, NEW_POSTS_CHECK_INTERVAL_MS)
    document.addEventListener('visibilitychange', check)
    return () => {
      cancelled = true
      clearInterval(timer)
      document.removeEventListener('visibilitychange', check)
    }
  }, [status, tab])

  /** 最新の20件を取り直して一番上へ（「↑ N件の新しい投稿」・表示中のタブ・ヘッダーの「ホーム」を押したとき） */
  const refresh = useCallback(() => {
    window.scrollTo(0, 0)
    void loadFirstPage()
  }, [loadFirstPage])

  /**
   * モーダルの「最新の投稿を見る」。取り直しが終わるのを待たずに、お知らせ（モーダル・ボタン）を消す。
   * 途中の件数の確認の結果でお知らせがまた出ないよう、その結果も捨てる
   */
  function showNewPosts() {
    generationRef.current += 1
    setNewCount(0)
    refresh()
  }

  const dismissNewPosts = useCallback(() => setDismissedCount(newCount), [newCount])

  // 投稿の作成・編集・削除のモーダルを開いている間は重ねて出さない（閉じたあとに出す）
  const showNewPostsDialog = newCount > dismissedCount && !composing && !actions.dialogOpen

  // ヘッダーの「ホーム」を、ホームを表示中にもう一度押したとき（AppLayout が state に refresh を付けて遷移してくる）
  useEffect(() => {
    if ((location.state as { refresh?: boolean } | null)?.refresh) {
      navigate(location.pathname, { replace: true, state: null })
      if (status === 'ready') refresh()
    }
  }, [location, navigate, refresh, status])

  /** 表示中のタブをもう一度押したら、URL は変えずに最新を取り直す */
  function handleTabClick(event: MouseEvent, target: TimelineTab) {
    if (target !== tab) return
    event.preventDefault()
    refresh()
  }

  async function handleCreate(content: string) {
    const created = await createPost(content)
    setComposing(false)
    // 自分の投稿はどちらのタブにも出るので、先頭に足す（新しい投稿の件数には自分の投稿は含まれない）
    setPosts((prev) => [created, ...prev])
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
        <NavLink
          to="/"
          end
          className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}
          onClick={(e) => handleTabClick(e, 'following')}
        >
          フォロー中
        </NavLink>
        <NavLink
          to="/all"
          className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}
          onClick={(e) => handleTabClick(e, 'all')}
        >
          全体
        </NavLink>
      </nav>

      {newCount > 0 && (
        <div className={styles.newPostsArea}>
          <button type="button" className={styles.newPosts} onClick={refresh}>
            ↑ {newCount > 99 ? '99+' : newCount}件の新しい投稿
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
          <PostCard
            key={post.id}
            post={post}
            onEdit={actions.startEdit}
            onDelete={actions.startDelete}
            onLikeChange={handleLikeChange}
            onLikeError={handleLikeError}
          />
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

      {showNewPostsDialog && <NewPostsDialog count={newCount} onShow={showNewPosts} onLater={dismissNewPosts} />}
      {composing && <PostComposer mode="create" onSubmit={handleCreate} onClose={() => setComposing(false)} />}
      {actions.elements}
    </>
  )
}
