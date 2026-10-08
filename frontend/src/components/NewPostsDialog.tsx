import { useEffect, useId, useRef } from 'react'
import styles from './Modal.module.css'

interface NewPostsDialogProps {
  /** 新しい投稿の件数（100件で打ち切られている。99件を超えたら「99+」と出す） */
  count: number
  /** 「最新の投稿を見る」：一番上へ移動して最新を取り直す */
  onShow: () => void
  /** 「あとで」・Esc キー：閉じる（タブの下の小さなボタンは残る） */
  onLater: () => void
}

/** 新しい投稿のお知らせ（F-24）。スクロール位置に関係なく画面の中央に出す */
export function NewPostsDialog({ count, onShow, onLater }: NewPostsDialogProps) {
  const titleId = useId()
  const showRef = useRef<HTMLButtonElement>(null)

  // 開いたときだけフォーカスする（開いている間に件数が変わっても、選んでいるボタンを動かさない）
  useEffect(() => {
    showRef.current?.focus()
  }, [])

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onLater()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onLater])

  return (
    <div className={styles.overlay}>
      <dialog open className={`${styles.modal} ${styles.small}`} aria-modal="true" aria-labelledby={titleId}>
        <h2 id={titleId} className={styles.title}>
          新しい投稿があります
        </h2>
        <p className={styles.message}>{count > 99 ? '99+' : count}件の新しい投稿があります。最新の投稿を表示しますか？</p>
        <div className={styles.buttons}>
          <button type="button" className={styles.button} onClick={onLater}>
            あとで
          </button>
          <button type="button" ref={showRef} className={`${styles.button} ${styles.primary}`} onClick={onShow}>
            最新の投稿を見る
          </button>
        </div>
      </dialog>
    </div>
  )
}
