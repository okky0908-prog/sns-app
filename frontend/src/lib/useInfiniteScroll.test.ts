// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { useInfiniteScroll } from './useInfiniteScroll'

/** jsdom には IntersectionObserver がないので、作られた見張りを記録する偽物を置く */
class FakeObserver {
  static created: FakeObserver[] = []
  readonly callback: (entries: { isIntersecting: boolean }[]) => void
  readonly options: { rootMargin?: string }
  observed: Element[] = []
  disconnected = false
  constructor(callback: (entries: { isIntersecting: boolean }[]) => void, options: { rootMargin?: string }) {
    this.callback = callback
    this.options = options
    FakeObserver.created.push(this)
  }
  observe(element: Element) {
    this.observed.push(element)
  }
  disconnect() {
    this.disconnected = true
  }
  /** 目印が画面に近づいた（isIntersecting = true）・離れた */
  fire(isIntersecting: boolean) {
    this.callback([{ isIntersecting }])
  }
}

beforeEach(() => {
  FakeObserver.created = []
  vi.stubGlobal('IntersectionObserver', FakeObserver)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const sentinel = () => document.createElement('div')

describe('useInfiniteScroll：一番下の目印が画面に近づいたら続きを読み込む', () => {
  test('目印が近づいたら loadMore を呼び、離れたままなら呼ばない', () => {
    const loadMore = vi.fn()
    const { result } = renderHook(() => useInfiniteScroll<HTMLDivElement>(true, loadMore))
    const element = sentinel()

    act(() => result.current(element))

    const [observer] = FakeObserver.created
    expect(observer.observed).toEqual([element])
    // 下まで行き着く前に読み込み始めるよう、400px 手前から反応する
    expect(observer.options.rootMargin).toBe('400px')
    observer.fire(false)
    expect(loadMore).not.toHaveBeenCalled()
    observer.fire(true)
    expect(loadMore).toHaveBeenCalledTimes(1)
  })

  test('読み込めないとき（読み込み中・続きがない・失敗した）は見張らない', () => {
    const { result } = renderHook(() => useInfiniteScroll<HTMLDivElement>(false, vi.fn()))

    act(() => result.current(sentinel()))

    expect(FakeObserver.created).toHaveLength(0)
  })

  test('目印がまだ画面にない間は見張らない', () => {
    renderHook(() => useInfiniteScroll<HTMLDivElement>(true, vi.fn()))
    expect(FakeObserver.created).toHaveLength(0)
  })

  test('目印があとから出たら、その時点で見張り始める', () => {
    const { result } = renderHook(() => useInfiniteScroll<HTMLDivElement>(true, vi.fn()))

    act(() => result.current(sentinel()))

    expect(FakeObserver.created).toHaveLength(1)
  })

  test('読み込めなくなったら（読み込みを始めた）、見張りをやめる', () => {
    const { result, rerender } = renderHook(
      ({ canLoadMore }) => useInfiniteScroll<HTMLDivElement>(canLoadMore, vi.fn()),
      { initialProps: { canLoadMore: true } },
    )
    act(() => result.current(sentinel()))

    rerender({ canLoadMore: false })

    expect(FakeObserver.created[0].disconnected).toBe(true)
    expect(FakeObserver.created).toHaveLength(1)
  })

  test('画面を離れたら、見張りをやめる', () => {
    const { result, unmount } = renderHook(() => useInfiniteScroll<HTMLDivElement>(true, vi.fn()))
    act(() => result.current(sentinel()))

    unmount()

    expect(FakeObserver.created[0].disconnected).toBe(true)
  })
})
