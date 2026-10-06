import { useEffect, useMemo, useRef, type ReactNode } from 'react'
import { ApiError, streamTimeline } from '../api/client'
import { TimelineStreamContext, type StreamListener } from './timelineStreamContext'

const MIN_RETRY_MS = 1000
const MAX_RETRY_MS = 30_000

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      resolve()
    })
  })
}

/**
 * タイムラインの通知（SSE）の接続を、アプリ全体で1本だけ持つ（docs/feature-specs/03_timeline.md）。
 * - 切れたら自動でつなぎ直す（1秒から始めて、失敗が続くと最大30秒まで間をあける）
 * - つなぎ直したときは、切れていた間の通知を取りこぼしたかもしれないので、画面に reconnected を知らせる
 * - ログイン後の画面（AppLayout）の中に置くので、ログアウトすると接続も閉じる
 */
export function TimelineStreamProvider({ children }: { children: ReactNode }) {
  const listeners = useRef(new Set<StreamListener>())

  useEffect(() => {
    const controller = new AbortController()
    const { signal } = controller
    const emit: StreamListener = (event) => listeners.current.forEach((listener) => listener(event))

    void (async () => {
      let connectedBefore = false
      let retryMs = MIN_RETRY_MS
      while (!signal.aborted) {
        try {
          await streamTimeline(emit, () => {
            if (connectedBefore) emit({ type: 'reconnected' })
            connectedBefore = true
            retryMs = MIN_RETRY_MS
          }, signal)
        } catch (err) {
          if (signal.aborted) return
          // リフレッシュトークンも切れていた（ログアウト扱いになる）ときは、つなぎ直さない
          if (err instanceof ApiError && err.status === 401) return
        }
        await sleep(retryMs, signal)
        retryMs = Math.min(retryMs * 2, MAX_RETRY_MS)
      }
    })()

    return () => controller.abort()
  }, [])

  const value = useMemo(
    () => ({
      subscribe: (listener: StreamListener) => {
        listeners.current.add(listener)
        return () => {
          listeners.current.delete(listener)
        }
      },
    }),
    [],
  )

  return <TimelineStreamContext.Provider value={value}>{children}</TimelineStreamContext.Provider>
}
