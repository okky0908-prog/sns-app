import { Link, NavLink, Outlet, useNavigate } from 'react-router'
import { useAuth } from '../auth/authContext'
import { TimelineStreamProvider } from '../timeline/TimelineStreamProvider'
import styles from './AppLayout.module.css'

/** ログイン後の画面の共通レイアウト（共通ヘッダー＋中央の1カラム）。検索・プロフィールへのリンクは各機能の実装時に追加する */
export function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <TimelineStreamProvider>
      <header className={styles.header}>
        <div className={styles.inner}>
          <Link to="/" className={styles.logo}>
            SNSアプリ
          </Link>
          <nav className={styles.nav}>
            <NavLink to="/" end className={({ isActive }) => (isActive ? styles.active : undefined)}>
              ホーム
            </NavLink>
            {user && (
              <span className={styles.user} title={`${user.displayName}（@${user.username}）としてログイン中`}>
                @{user.username}
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
    </TimelineStreamProvider>
  )
}
