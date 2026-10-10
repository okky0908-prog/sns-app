import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import { searchUsers } from '../api/client'
import type { FollowState, UserListItem } from '../api/types'
import { Toast } from '../components/Toast'
import { UserListRow } from '../components/UserListRow'
import { withoutDuplicates } from '../lib/list'
import { useInfiniteScroll } from '../lib/useInfiniteScroll'
import styles from './SearchPage.module.css'

/** 入力が止まってからこの時間がたったら検索する（1文字ごとにリクエストしない） */
const DEBOUNCE_MS = 300
const KEYWORD_MAX = 50

/**
 * S-10 のルート。ヘッダーの検索ボックスから来るたびに画面を作り直して、その言葉で検索し直す（AppLayout が searchId を付ける）。
 * 検索画面の中で入力したときは URL の ?q= だけを書き換えるので、作り直さない（入力中の欄が消えない）
 */
export function SearchRoute() {
  const location = useLocation()
  const searchId = (location.state as { searchId?: number } | null)?.searchId ?? 'first'
  return <SearchPage key={searchId} />
}

/** S-10 ユーザー検索（/search?q=キーワード。docs/feature-specs/08_user_search.md） */
function SearchPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const location = useLocation()
  /** 入力欄の文字 */
  const [input, setInput] = useState(() => searchParams.get('q') ?? '')
  /** 結果を出しているキーワード（URL の ?q= と同じ） */
  const keyword = (searchParams.get('q') ?? '').trim()
  /**
   * 読み込んだ結果。どのキーワードの結果かも一緒に持ち、今のキーワードと違えば「検索中」とみなす。
   * （読み込み中かどうかを手で切り替えると、URL が変わらず検索が始まらなかったときに「検索中」のまま残ってしまう）
   */
  const [result, setResult] = useState<{
    keyword: string
    status: 'ready' | 'error'
    users: UserListItem[]
    nextCursor: string | null
  } | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const [toast, setToast] = useState<{ id: number; message: string } | null>(null)
  /** 検索のたびに増やす。前のキーワードの結果があとから届いても、番号が違えば捨てる（古い結果で上書きしない） */
  const requestIdRef = useRef(0)
  const timerRef = useRef<number | undefined>(undefined)

  const status = result?.keyword === keyword ? result.status : 'loading'
  const users = status === 'ready' && result ? result.users : []
  const nextCursor = status === 'ready' && result ? result.nextCursor : null

  /** URL の ?q= を書き換える（履歴は増やさない）。結果の読み込みは keyword が変わったことで始まる */
  const commit = useCallback(
    (value: string) => {
      const q = value.trim()
      setSearchParams(q ? { q } : {}, { replace: true, state: location.state })
    },
    [setSearchParams, location.state],
  )

  /** 1ページ目を読み込む */
  const loadFirstPage = useCallback((target: string) => {
    const requestId = ++requestIdRef.current
    searchUsers(target)
      .then((res) => {
        if (requestId !== requestIdRef.current) return
        setResult({ keyword: target, status: 'ready', users: res.items, nextCursor: res.nextCursor })
        setMoreFailed(false)
      })
      .catch(() => {
        if (requestId === requestIdRef.current) setResult({ keyword: target, status: 'error', users: [], nextCursor: null })
      })
  }, [])

  // キーワードが変わったら1ページ目を読み込む
  useEffect(() => {
    loadFirstPage(keyword)
  }, [keyword, loadFirstPage])

  // 画面を離れるときに、まだ待っている自動検索を止める
  useEffect(() => () => window.clearTimeout(timerRef.current), [])

  function handleInput(value: string) {
    setInput(value)
    window.clearTimeout(timerRef.current)
    timerRef.current = window.setTimeout(() => commit(value), DEBOUNCE_MS)
  }

  /** Enter ならすぐ検索する */
  function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    window.clearTimeout(timerRef.current)
    commit(input)
  }

  function clear() {
    window.clearTimeout(timerRef.current)
    setInput('')
    commit('')
  }

  function retry() {
    setResult(null)
    loadFirstPage(keyword)
  }

  const loadMore = useCallback(async () => {
    if (!nextCursor) return
    const requestId = requestIdRef.current
    setLoadingMore(true)
    setMoreFailed(false)
    try {
      const res = await searchUsers(keyword, nextCursor)
      if (requestId !== requestIdRef.current) return
      setResult((prev) =>
        prev ? { ...prev, users: [...prev.users, ...withoutDuplicates(res.items, prev.users)], nextCursor: res.nextCursor } : prev,
      )
    } catch {
      if (requestId === requestIdRef.current) setMoreFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }, [keyword, nextCursor])

  const hasNext = nextCursor !== null
  const sentinelRef = useInfiniteScroll<HTMLDivElement>(status === 'ready' && hasNext && !loadingMore && !moreFailed, loadMore)

  const handleFollowChange = useCallback(
    (userId: number, state: FollowState) =>
      setResult((prev) =>
        prev
          ? { ...prev, users: prev.users.map((u) => (u.id === userId ? { ...u, followedByMe: state.following } : u)) }
          : prev,
      ),
    [],
  )
  const handleError = useCallback((message: string) => setToast((prev) => ({ id: (prev?.id ?? 0) + 1, message })), [])
  const clearToast = useCallback(() => setToast(null), [])

  const heading = keyword ? `「${keyword}」の検索結果` : '最近参加したユーザー'
  return (
    <>
      <div className={styles.header}>
        <search>
          <form className={styles.form} onSubmit={handleSubmit}>
            <input
              type="text"
              className={styles.input}
              placeholder="@ユーザー名・表示名で検索"
              aria-label="ユーザーを検索"
              maxLength={KEYWORD_MAX}
              value={input}
              // eslint-disable-next-line jsx-a11y/no-autofocus -- 検索画面を開いたらすぐ入力できるようにする
              autoFocus
              onChange={(e) => handleInput(e.target.value)}
            />
            {input && (
              <button type="button" className={styles.clear} aria-label="入力をクリア" onClick={clear}>
                ×
              </button>
            )}
          </form>
        </search>
      </div>

      <section aria-label={heading} aria-busy={status === 'loading' || loadingMore}>
        <h1 className={styles.heading}>{heading}</h1>
        {status === 'loading' && <p className={styles.message}>検索中…</p>}
        {status === 'error' && (
          <div className={styles.message}>
            <p>検索に失敗しました。</p>
            <button type="button" className={styles.more} onClick={retry}>
              再試行
            </button>
          </div>
        )}
        {status === 'ready' && users.length === 0 && (
          <p className={styles.message}>
            {keyword ? `「${keyword}」に一致するユーザーは見つかりませんでした` : 'まだユーザーがいません'}
          </p>
        )}
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
