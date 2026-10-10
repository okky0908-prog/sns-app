import { useCallback, useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { ApiError, errorMessage, isApiError } from '../api/client'
import type { PostImage } from '../api/types'
import { countChars } from '../lib/format'
import { IMAGE_ACCEPT, imageFileError } from '../lib/imageFile'
import { ConfirmDialog } from './ConfirmDialog'
import modalStyles from './Modal.module.css'
import styles from './PostComposer.module.css'

const MAX_LENGTH = 280
const MAX_IMAGES = 4

/** 選んだ画像（プレビュー用の URL つき） */
interface SelectedImage {
  file: File
  url: string
}

interface PostComposerProps {
  mode: 'create' | 'edit'
  initialContent?: string
  /** 編集のときの投稿の画像。表示するだけで、追加・削除はできない */
  initialImages?: PostImage[]
  /** 送信。失敗したら例外を投げる（モーダルは開いたままエラーを表示する）。images は作成のときだけ（選んだ順） */
  onSubmit: (content: string, images: File[]) => Promise<void>
  onClose: () => void
}

/** S-04 投稿作成 / S-05 投稿編集のモーダル（docs/screens.md・docs/feature-specs/02_post.md） */
export function PostComposer({ mode, initialContent = '', initialImages = [], onSubmit, onClose }: PostComposerProps) {
  const [content, setContent] = useState(initialContent)
  const [images, setImages] = useState<SelectedImage[]>([])
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [confirmingClose, setConfirmingClose] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  // 画面を閉じるときに、プレビュー用の URL をまとめて解放する（ref で今の一覧を持つ）
  const imagesRef = useRef(images)
  useEffect(() => {
    imagesRef.current = images
  }, [images])
  useEffect(() => () => imagesRef.current.forEach((image) => URL.revokeObjectURL(image.url)), [])

  // 前後の空白・改行を除いて数える（サーバーと同じ）
  const length = countChars(content.trim())
  // 本文と画像のどちらか一方は必要（編集は、もとの投稿に画像があれば本文を空にできる）
  const hasImages = images.length > 0 || initialImages.length > 0
  const canSubmit = (length >= 1 || hasImages) && length <= MAX_LENGTH && !submitting
  const dirty = content !== initialContent || images.length > 0

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

  /** 画像を選んだ：選べるものだけを、4枚になるまで後ろに足す（選べなかった理由はエラーに出す） */
  function handleFiles(e: ChangeEvent<HTMLInputElement>) {
    const files = [...(e.target.files ?? [])]
    e.target.value = '' // 同じファイルを選び直しても change が起きるように
    let message: string | null = null
    const added: SelectedImage[] = []
    for (const file of files) {
      const fileError = imageFileError(file)
      if (fileError) {
        message = fileError
      } else if (images.length + added.length >= MAX_IMAGES) {
        message = '画像は4枚まで添付できます'
      } else {
        added.push({ file, url: URL.createObjectURL(file) })
      }
    }
    setImages((prev) => [...prev, ...added])
    setError(message)
  }

  function removeImage(target: SelectedImage) {
    URL.revokeObjectURL(target.url)
    setImages((prev) => prev.filter((image) => image !== target))
    setError(null)
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!canSubmit) return
    setSubmitting(true)
    setError(null)
    try {
      await onSubmit(
        content,
        images.map((image) => image.file),
      )
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors.length > 0) setError(err.fieldErrors[0].message)
      else if (isApiError(err, 'PAYLOAD_TOO_LARGE')) setError('画像のサイズが大きすぎます')
      else setError(errorMessage(err))
      setSubmitting(false)
    }
  }

  const title = mode === 'create' ? '新しい投稿' : '投稿を編集'
  const shownImages = mode === 'create' ? images : initialImages.map((image) => ({ url: image.url, file: null }))
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
            {shownImages.length > 0 && (
              <ul className={styles.images} aria-label="添付する画像">
                {shownImages.map((image, index) => (
                  <li key={image.url} className={styles.image}>
                    <img src={image.url} alt={`画像${index + 1}`} />
                    {/* 編集では画像の追加・削除はできない（表示するだけ） */}
                    {mode === 'create' && (
                      <button
                        type="button"
                        className={styles.remove}
                        aria-label={`画像${index + 1}を外す`}
                        onClick={() => removeImage(images[index])}
                      >
                        ×
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {error && (
              <p className={styles.error} role="alert">
                {error}
              </p>
            )}
          </div>
          <div className={styles.footer}>
            {mode === 'create' && (
              <>
                <button
                  type="button"
                  className={styles.addImage}
                  disabled={images.length >= MAX_IMAGES || submitting}
                  onClick={() => fileRef.current?.click()}
                >
                  🖼 画像を追加
                </button>
                <input
                  ref={fileRef}
                  type="file"
                  accept={IMAGE_ACCEPT}
                  multiple
                  className={styles.fileInput}
                  aria-label="画像を選ぶ"
                  onChange={handleFiles}
                />
              </>
            )}
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
