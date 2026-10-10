import { useState, type FormEvent } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router'
import { useAuth } from '../auth/authContext'
import { Avatar } from './Avatar'
import styles from './AppLayout.module.css'

/** ホームを表示中にもう一度押したら、タイムラインが最新を取り直す（TimelinePage が受け取る） */
const REFRESH = { refresh: true }

/** ログイン後の画面の共通レイアウト（共通ヘッダー＋中央の1カラム） */
export function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [keyword, setKeyword] = useState('')

  /**
   * ヘッダーの検索ボックスで Enter：S-10 ユーザー検索を開く（空なら最近参加したユーザー）。
   * 検索画面を表示中でも、押すたびにその言葉で検索し直せるよう、毎回違う searchId を渡す（SearchPage が作り直される）
   */
  function handleSearch(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const q = keyword.trim()
    navigate(q ? `/search?q=${encodeURIComponent(q)}` : '/search', { state: { searchId: Date.now() } })
    setKeyword('')
  }

  async function handleLogout() {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <>
      <header className={styles.header}>
        <div className={styles.inner}>
          <Link to="/" state={REFRESH} className={styles.logo}>
            SNSアプリ
          </Link>
          <search className={styles.search}>
            <form onSubmit={handleSearch}>
              <input
                type="search"
                className={styles.searchInput}
                placeholder="🔍 ユーザーを検索"
                aria-label="ユーザーを検索"
                maxLength={50}
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
            </form>
          </search>
          <nav className={styles.nav}>
            <NavLink to="/" state={REFRESH} end className={({ isActive }) => (isActive ? styles.active : undefined)}>
              ホーム
            </NavLink>
            {user && (
              <NavLink
                to={`/users/${encodeURIComponent(user.username)}`}
                className={({ isActive }) => (isActive ? styles.active : undefined)}
              >
                プロフィール
              </NavLink>
            )}
            {user && (
              // ログイン中のアカウント（プロフィール画像と表示名）。プロフィール編集で変えたら、すぐここにも反映される
              <span className={styles.user} title={`${user.displayName}（@${user.username}）としてログイン中`}>
                <Avatar displayName={user.displayName} iconUrl={user.iconUrl} size={28} />
                <span className={styles.userName}>{user.displayName}</span>
              </span>
            )}
            <button type="button" className={styles.logout} onClick={handleLogout}>
              ログアウト
            </button>
          </nav>
        </div>
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
    </>
  )
}
