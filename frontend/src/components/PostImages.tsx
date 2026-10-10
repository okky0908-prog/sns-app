import { useState } from 'react'
import type { PostImage } from '../api/types'
import { ImageViewer } from './ImageViewer'
import styles from './PostImages.module.css'

/**
 * 投稿カードの画像（1〜4枚）。枚数に応じて並べ方を変える（1枚：大きく1つ、2枚：左右、3枚：左に大きく1つ＋右に2つ、4枚：2×2）。
 * 押すと拡大表示する。ボタンなので、押しても投稿詳細へは移動しない（PostCard がボタンのクリックを除外している）
 */
export function PostImages({ images }: { images: PostImage[] }) {
  const [viewing, setViewing] = useState<number | null>(null)
  if (images.length === 0) return null
  return (
    <>
      <div className={`${styles.grid} ${styles[`count${images.length}`]}`}>
        {images.map((image, index) => (
          <button
            key={image.url}
            type="button"
            className={styles.item}
            aria-label={`画像 ${index + 1} / ${images.length} を拡大`}
            onClick={() => setViewing(index)}
          >
            <img src={image.url} alt="" loading="lazy" />
          </button>
        ))}
      </div>
      {viewing !== null && <ImageViewer images={images} startIndex={viewing} onClose={() => setViewing(null)} />}
    </>
  )
}
