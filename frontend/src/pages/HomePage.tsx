import { useNavigate } from 'react-router'
import { useAuth } from '../auth/authContext'
import styles from './HomePage.module.css'

/** ログイン後の仮画面。タイムライン（S-03）を実装するまでの間、ログインできたことを確認するために使う */
export function HomePage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  if (!user) return null // RequireAuth の内側なので通常は起きない

  function handleLogout() {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <p className={styles.icon} aria-hidden="true">
          ✅
        </p>
        <h1 className={styles.title}>ログイン成功</h1>
        <p className={styles.user}>
          <strong>{user.displayName}</strong>さん（@{user.username}）としてログインしています。
        </p>
        <p className={styles.note}>タイムラインなどの画面は、これから実装します。</p>
        <button type="button" className={styles.logout} onClick={handleLogout}>
          ログアウト
        </button>
      </div>
    </main>
  )
}
