import { Navigate, Outlet } from 'react-router'
import { useAuth } from './authContext'
import styles from './RouteGuards.module.css'

function Loading() {
  return <p className={styles.loading}>読み込み中…</p>
}

/** ログインが必要な画面。未ログインならログイン画面へ移動する */
export function RequireAuth() {
  const { user, loading } = useAuth()
  if (loading) return <Loading />
  return user ? <Outlet /> : <Navigate to="/login" replace />
}

/** ログイン・新規登録画面。ログイン済みならホームへ移動する */
export function PublicOnly() {
  const { user, loading } = useAuth()
  if (loading) return <Loading />
  return user ? <Navigate to="/" replace /> : <Outlet />
}
