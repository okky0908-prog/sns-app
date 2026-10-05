import { useCallback, useEffect, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router'
import { createPost, fetchTimeline, type TimelineTab } from '../api/client'
import type { Post } from '../api/types'
import { PostCard } from '../components/PostCard'
import { PostComposer } from '../components/PostComposer'
import { usePostActions } from '../posts/usePostActions'
import styles from './TimelinePage.module.css'

/** すでに表示している投稿と同じ ID のものを除いて後ろに足す（「もっと見る」で投稿がずれて重複するのを防ぐ） */
function appendUnique(current: Post[], more: Post[]): Post[] {
  const ids = new Set(current.map((p) => p.id))
  return [...current, ...more.filter((p) => !ids.has(p.id))]
}

/** S-03 タイムライン（docs/screens.md・docs/feature-specs/03_timeline.md） */
export function TimelinePage({ tab }: { tab: TimelineTab }) {
  const location = useLocation()
  const navigate = useNavigate()
  const [posts, setPosts] = useState<Post[]>([])
  const [page, setPage] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const [composing, setComposing] = useState(false)

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
      fetchTimeline(tab, 0)
        .then((res) => {
          if (isCancelled()) return
          setPosts(res.items)
          setPage(res.page)
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

  const loadMore = useCallback(async () => {
    setLoadingMore(true)
    setMoreFailed(false)
    try {
      const res = await fetchTimeline(tab, page + 1)
      setPosts((prev) => appendUnique(prev, res.items))
      setPage(res.page)
      setHasNext(res.hasNext)
    } catch {
      setMoreFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }, [tab, page])

  async function handleCreate(content: string) {
    const created = await createPost(content)
    setComposing(false)
    // 自分の投稿はどちらのタブにも出るので、先頭に足す
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

      <section aria-label="投稿一覧" aria-busy={status === 'loading'}>
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
          <div className={styles.moreArea}>
            {moreFailed && <p className={styles.error}>読み込みに失敗しました</p>}
            <button type="button" className={styles.more} onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? '読み込み中…' : moreFailed ? '再試行' : 'もっと見る'}
            </button>
          </div>
        )}
      </section>

      {composing && <PostComposer mode="create" onSubmit={handleCreate} onClose={() => setComposing(false)} />}
      {actions.elements}
    </>
  )
}
