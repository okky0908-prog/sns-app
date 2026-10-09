import { useEffect, useState } from 'react'

/** 一番下の目印がここまで近づいたら続きを読み込む（下まで行き着く前に読み込み始める） */
const PRELOAD_MARGIN = '400px'

/**
 * 無限スクロール（docs/feature-specs/03_timeline.md）。返した関数を一覧の一番下の目印の ref に渡すと、
 * 目印が画面に近づいたときに loadMore を呼ぶ。canLoadMore が false の間（読み込み中・続きがない・失敗した）は呼ばない。
 *
 * 目印は、プロフィールの読み込みを待ってから出るなど、あとから画面に出ることがある。目印が出た・消えたことにも反応できるよう、
 * ref はオブジェクトではなく関数（コールバック）で受け取り、要素を state に持つ
 */
export function useInfiniteScroll<T extends HTMLElement>(canLoadMore: boolean, loadMore: () => void) {
  const [sentinel, setSentinel] = useState<T | null>(null)

  useEffect(() => {
    if (!sentinel || !canLoadMore) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) loadMore()
      },
      { rootMargin: PRELOAD_MARGIN },
    )
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [sentinel, canLoadMore, loadMore])

  return setSentinel
}
