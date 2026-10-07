import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError, errorMessage } from '../api/client'
import { countChars } from '../lib/format'
import { ConfirmDialog } from './ConfirmDialog'
import modalStyles from './Modal.module.css'
import styles from './PostComposer.module.css'

const MAX_LENGTH = 280

interface PostComposerProps {
  mode: 'create' | 'edit'
  initialContent?: string
  /** 送信。失敗したら例外を投げる（モーダルは開いたままエラーを表示する） */
  onSubmit: (content: string) => Promise<void>
  onClose: () => void
}

/** S-04 投稿作成 / S-05 投稿編集のモーダル（docs/screens.md・docs/feature-specs/02_post.md） */
export function PostComposer({ mode, initialContent = '', onSubmit, onClose }: PostComposerProps) {
  const [content, setContent] = useState(initialContent)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [confirmingClose, setConfirmingClose] = useState(false)

  // 前後の空白・改行を除いて数える（サーバーと同じ）
  const length = countChars(content.trim())
  const canSubmit = length >= 1 && length <= MAX_LENGTH && !submitting
  const dirty = content !== initialContent

  const requestClose = useCallback(() => {
    if (submitting) return
    if (dirty) setConfirmingClose(true)
    else onClose()
  }, [dirty, submitting, onClose])

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !confirmingClose) requestClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [requestClose, confirmingClose])

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!canSubmit) return
    setSubmitting(true)
    setError(null)
    try {
      await onSubmit(content)
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors.length > 0) setError(err.fieldErrors[0].message)
      else setError(errorMessage(err))
      setSubmitting(false)
    }
  }

  const title = mode === 'create' ? '新しい投稿' : '投稿を編集'
  return (
    <div className={modalStyles.overlay}>
      <dialog open className={modalStyles.modal} aria-modal="true" aria-label={title}>
        <form onSubmit={handleSubmit}>
        <div className={styles.header}>
          <h2 className={styles.title}>{title}</h2>
          <button type="button" className={styles.close} aria-label="閉じる" onClick={requestClose}>
            ×
          </button>
        </div>
        <div className={styles.body}>
          <textarea
            className={styles.textarea}
            rows={5}
            placeholder="いまどうしてる？"
            aria-label="本文"
            autoFocus
            value={content}
            onChange={(e) => setContent(e.target.value)}
          />
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
        </div>
        <div className={styles.footer}>
          <span className={`${styles.counter} ${length > MAX_LENGTH ? styles.over : ''}`} aria-live="polite">
            {length} / {MAX_LENGTH}
          </span>
          <button type="submit" className={styles.submit} disabled={!canSubmit}>
            {submitting ? (mode === 'create' ? '投稿中…' : '保存中…') : mode === 'create' ? '投稿する' : '保存する'}
          </button>
        </div>
        </form>
      </dialog>
      {confirmingClose && (
        <ConfirmDialog
          message="入力中の内容は破棄されます。よろしいですか？"
          confirmLabel="破棄する"
          onConfirm={onClose}
          onCancel={() => setConfirmingClose(false)}
        />
      )}
    </div>
  )
}
