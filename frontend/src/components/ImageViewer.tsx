import { useEffect, useRef, useState } from 'react'
import type { PostImage } from '../api/types'
import { useOverlayOpen } from '../lib/overlay'
import styles from './ImageViewer.module.css'

interface ImageViewerProps {
  images: PostImage[]
  /** 最初に表示する画像（0始まり） */
  startIndex: number
  onClose: () => void
}

/**
 * 投稿の画像の拡大表示（docs/screens.md「投稿カード」の画像）。
 * Esc・画像の外側のクリック・× で閉じる。複数枚なら ‹ › ボタンと左右の矢印キーで前後に移動する
 */
export function ImageViewer({ images, startIndex, onClose }: ImageViewerProps) {
  // 開いている間は、新しい投稿のお知らせ（モーダル）を出さない（拡大表示の裏に隠れて開かないように）
  useOverlayOpen()
  const [index, setIndex] = useState(startIndex)
  const closeRef = useRef<HTMLButtonElement>(null)
  const multiple = images.length > 1
  const move = (step: number) => setIndex((i) => (i + step + images.length) % images.length)

  useEffect(() => {
    closeRef.current?.focus()
  }, [])

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (multiple && e.key === 'ArrowRight') setIndex((i) => (i + 1) % images.length)
      else if (multiple && e.key === 'ArrowLeft') setIndex((i) => (i - 1 + images.length) % images.length)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose, multiple, images.length])

  return (
    // 画像の外側（暗い部分）を押したら閉じる。キーボードでは Esc で閉じられる。
    // 投稿カードの中に描画されるので、ここでのクリックをカードに伝えない（伝わると投稿詳細へ移動してしまう）
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      className={styles.overlay}
      onClick={(e) => {
        e.stopPropagation()
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <dialog open className={styles.dialog} aria-modal="true" aria-label={`画像 ${index + 1} / ${images.length}`}>
        <img className={styles.image} src={images[index].url} alt={`画像 ${index + 1} / ${images.length}`} />
        {multiple && (
          <>
            <button type="button" className={`${styles.nav} ${styles.prev}`} aria-label="前の画像" onClick={() => move(-1)}>
              ‹
            </button>
            <button type="button" className={`${styles.nav} ${styles.next}`} aria-label="次の画像" onClick={() => move(1)}>
              ›
            </button>
            <p className={styles.position}>
              {index + 1} / {images.length}
            </p>
          </>
        )}
        <button ref={closeRef} type="button" className={styles.close} aria-label="閉じる" onClick={onClose}>
          ×
        </button>
      </dialog>
    </div>
  )
}
