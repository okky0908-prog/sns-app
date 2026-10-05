import { useEffect } from 'react'
import styles from './Toast.module.css'

interface ToastProps {
  message: string
  error?: boolean
  onDone: () => void
}

/** 画面下に短く出すお知らせ（「投稿しました」など）。2.5秒で消える。同じ文言をもう一度出すときは key を変えて作り直す */
export function Toast({ message, error = false, onDone }: ToastProps) {
  useEffect(() => {
    const timer = setTimeout(onDone, 2500)
    return () => clearTimeout(timer)
  }, [onDone])

  return <output className={`${styles.toast} ${error ? styles.error : ''}`}>{message}</output>
}
