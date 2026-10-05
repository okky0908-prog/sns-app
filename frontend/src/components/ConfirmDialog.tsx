import { useEffect, useRef } from 'react'
import styles from './Modal.module.css'

interface ConfirmDialogProps {
  message: string
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
}

/** 削除などの前に出す確認ダイアログ。Esc キーでキャンセル */
export function ConfirmDialog({ message, confirmLabel, onConfirm, onCancel }: ConfirmDialogProps) {
  const confirmRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    confirmRef.current?.focus()
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onCancel])

  return (
    <div className={styles.overlay}>
      <div className={`${styles.modal} ${styles.small}`} role="alertdialog" aria-modal="true" aria-label={message}>
        <p className={styles.message}>{message}</p>
        <div className={styles.buttons}>
          <button type="button" className={styles.button} onClick={onCancel}>
            キャンセル
          </button>
          <button type="button" ref={confirmRef} className={`${styles.button} ${styles.danger}`} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
