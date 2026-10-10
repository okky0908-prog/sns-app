import { useEffect, useSyncExternalStore } from 'react'

/*
 * 画面全体に重ねて出す表示（画像の拡大表示など）が開いているかを、離れた部品から知るための小さな仕組み。
 * 新しい投稿のお知らせ（モーダル）を、ほかの表示の裏に隠れて開かないよう、開いている間は出さないために使う
 */

let openCount = 0
const listeners = new Set<() => void>()

function notify() {
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** この部品が表示されている間、「重ねて出す表示が開いている」ことにする */
export function useOverlayOpen() {
  useEffect(() => {
    openCount += 1
    notify()
    return () => {
      openCount -= 1
      notify()
    }
  }, [])
}

/** 重ねて出す表示がどれか1つでも開いているか */
export function useAnyOverlayOpen(): boolean {
  return useSyncExternalStore(subscribe, () => openCount > 0)
}
