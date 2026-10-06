import { createContext, useContext, useEffect, useRef } from 'react'
import type { TimelineEvent } from '../api/types'

/** 画面に渡す通知。reconnected は「接続が切れていた間の通知を取りこぼしたかもしれない」という合図 */
export type StreamEvent = TimelineEvent | { type: 'reconnected' }
export type StreamListener = (event: StreamEvent) => void

export const TimelineStreamContext = createContext<{ subscribe: (listener: StreamListener) => () => void } | null>(null)

/** タイムラインの通知を受け取る。listener は最新のものが呼ばれる（再描画のたびに登録し直さない） */
export function useTimelineEvents(listener: StreamListener): void {
  const stream = useContext(TimelineStreamContext)
  const latest = useRef(listener)

  useEffect(() => {
    latest.current = listener
  })

  useEffect(() => {
    if (!stream) return
    return stream.subscribe((event) => latest.current(event))
  }, [stream])
}
