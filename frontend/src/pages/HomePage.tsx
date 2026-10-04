import { useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, fetchMe, NETWORK_ERROR_MESSAGE } from '../api/client'
import { useAuth } from '../auth/authContext'
import styles from './HomePage.module.css'

/** ログイン後の仮画面。タイムライン（S-03）を実装するまでの間、ログインできたことを確認するために使う */
export function HomePage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [checkResult, setCheckResult] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)

  if (!user) return null // RequireAuth の内側なので通常は起きない

  async function handleLogout() {
    await logout()
    navigate('/login', { replace: true })
  }

  // アクセストークンで認証が必要な API（/api/auth/me）を呼んでみる。
  // アクセストークンの期限（15分）が切れていても、リフレッシュトークンで自動的に再発行してから呼び直す
  async function handleCheck() {
    setChecking(true)
    try {
      const me = await fetchMe()
      setCheckResult(`サーバーで確認しました：@${me.username}（${new Date().toLocaleTimeString('ja-JP')}）`)
    } catch (err) {
      setCheckResult(err instanceof ApiError && err.status !== 0 ? err.message : NETWORK_ERROR_MESSAGE)
    } finally {
      setChecking(false)
    }
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
        <div className={styles.check}>
          <button type="button" className={styles.secondary} onClick={handleCheck} disabled={checking}>
            {checking ? '確認中…' : 'サーバーにログイン状態を問い合わせる'}
          </button>
          {checkResult && (
            <output className={styles.checkResult}>{checkResult}</output>
          )}
        </div>
        <button type="button" className={styles.logout} onClick={handleLogout}>
          ログアウト
        </button>
      </div>
    </main>
  )
}
