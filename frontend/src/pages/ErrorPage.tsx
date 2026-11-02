import { useEffect } from 'react'
import { useRouteError } from 'react-router'
import { reportError } from '../lib/errorReporting'
import styles from './ErrorPage.module.css'

/**
 * 画面の表示中にエラーが起きたときに、真っ白な画面の代わりに出す（router.tsx の errorElement）。
 * エラーはサーバーに送る（docs/observability.md「3.1 ログの種類」の画面のエラー）。
 * 画面の中の状態が壊れているかもしれないので、アプリの中の移動ではなく、ページごと読み込み直す
 */
export function ErrorPage() {
  const error = useRouteError()

  useEffect(() => {
    reportError('render', error)
  }, [error])

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>問題が発生しました</h1>
      <p className={styles.text}>画面を表示できませんでした。再読み込みしても直らないときは、時間をおいてもう一度お試しください。</p>
      <div className={styles.actions}>
        <button type="button" className={styles.reload} onClick={() => window.location.reload()}>
          再読み込み
        </button>
        {/* ページごと読み込み直すため、React Router の Link ではなくふつうのリンクにする */}
        <a href="/">ホームへ</a>
      </div>
    </main>
  )
}
