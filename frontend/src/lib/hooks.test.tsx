// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import type { ReactElement } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useAnyOverlayOpen, useOverlayOpen } from './overlay'
import { useGoBack } from './useGoBack'
import { useToast } from './useToast'

// useGoBack が使う画面の移動（react-router の navigate）を偽物にする
const navigate = vi.fn()
vi.mock('react-router', () => ({ useNavigate: () => navigate }))

afterEach(() => {
  navigate.mockReset()
  window.history.replaceState(null, '')
})

describe('useToast：画面下に短く出すお知らせ', () => {
  type ToastProps = { message: string; error: boolean; onDone: () => void }
  const toastOf = (element: unknown) => element as ReactElement<ToastProps> | null

  test('はじめは何も出さず、notify で文言を出す（error を省くとエラーではない）', () => {
    const { result } = renderHook(() => useToast())
    expect(result.current.toastElement).toBeNull()

    act(() => result.current.notify('投稿しました'))

    const toast = toastOf(result.current.toastElement)
    expect(toast?.props.message).toBe('投稿しました')
    expect(toast?.props.error).toBe(false)
  })

  test('エラーとして出せる', () => {
    const { result } = renderHook(() => useToast())

    act(() => result.current.notify('失敗しました', true))

    expect(toastOf(result.current.toastElement)?.props.error).toBe(true)
  })

  test('同じ文言を続けて出しても、出し直される（key が変わる）', () => {
    const { result } = renderHook(() => useToast())

    act(() => result.current.notify('コピーしました'))
    const firstKey = toastOf(result.current.toastElement)?.key
    act(() => result.current.notify('コピーしました'))

    expect(toastOf(result.current.toastElement)?.key).not.toBe(firstKey)
  })

  test('表示が終わったら（onDone）消える', () => {
    const { result } = renderHook(() => useToast())
    act(() => result.current.notify('投稿しました'))

    act(() => toastOf(result.current.toastElement)?.props.onDone())

    expect(result.current.toastElement).toBeNull()
  })
})

describe('useGoBack：画面の「←」（戻る）', () => {
  test.each([
    ['アプリの中から来た（履歴の番号が1以上）', { idx: 1 }, -1],
    ['URL を直接開いた（履歴の番号が0）', { idx: 0 }, '/'],
    ['履歴の情報がない', null, '/'],
  ])('%s', (_label, state, expected) => {
    window.history.replaceState(state, '')
    const { result } = renderHook(() => useGoBack('/'))

    result.current()

    expect(navigate).toHaveBeenCalledWith(expected)
  })
})

describe('overlay：重ねて出す表示が開いているか', () => {
  test('どれか1つでも開いている間だけ「開いている」になる', () => {
    const watcher = renderHook(() => useAnyOverlayOpen())
    expect(watcher.result.current).toBe(false)

    const dialog = renderHook(() => useOverlayOpen())
    expect(watcher.result.current).toBe(true)

    const viewer = renderHook(() => useOverlayOpen())
    dialog.unmount()
    // まだ1つ開いている
    expect(watcher.result.current).toBe(true)

    viewer.unmount()
    expect(watcher.result.current).toBe(false)
  })
})
