// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { ClientErrorReport } from '../api/types'
import { createErrorReporter, installGlobalErrorHandlers, MAX_REPORTS_PER_PAGE, type Reporter } from './errorReporting'

/*
 * 画面で起きたエラーを拾ってサーバーに送る仕組み（docs/observability.md「3.1 ログの種類」の画面のエラー）。
 * 送る先（api/client.ts の reportClientError）は偽物にして、送る中身を確かめる。
 */

const send = vi.fn<(report: ClientErrorReport) => Promise<void>>()
let path = '/posts/10'
let report: Reporter

beforeEach(() => {
  send.mockReset().mockResolvedValue(undefined)
  path = '/posts/10'
  report = createErrorReporter(send, () => path)
})

const sent = () => send.mock.calls.map(([r]) => r)

describe('送る中身', () => {
  test('Error なら、文言・スタックトレース・画面のパスを送る', () => {
    const error = new TypeError("Cannot read properties of undefined (reading 'id')")
    error.stack = 'TypeError: ...\n    at PostCard'

    report('render', error)

    expect(sent()).toEqual([
      {
        kind: 'render',
        message: "Cannot read properties of undefined (reading 'id')",
        stack: 'TypeError: ...\n    at PostCard',
        path: '/posts/10',
      },
    ])
  })

  test('画面の部品の並び（componentStack）があれば、スタックトレースの後ろにつなげる', () => {
    const error = new Error('壊れた')
    error.stack = 'Error: 壊れた\n    at X'

    report('render', error, '\n    at PostCard\n    at TimelinePage')

    expect(sent()[0].stack).toBe('Error: 壊れた\n    at X\n    at PostCard\n    at TimelinePage')
  })

  // 同値分割：Promise の失敗の理由は Error とは限らない
  test.each<[string, unknown, string]>([
    ['文字列', '読み込みに失敗', '読み込みに失敗'],
    ['オブジェクト', { code: 42 }, '{"code":42}'],
    ['undefined', undefined, 'undefined'],
    ['文言のない Error', new Error(''), 'Error'],
  ])('理由が%sでも、文言にして送る', (_label, reason, message) => {
    report('unhandledrejection', reason)

    expect(sent()[0].message).toBe(message)
  })

  test('JSON にできない値（自分を指すオブジェクトなど）でも、文言にして送る', () => {
    const circular: Record<string, unknown> = {}
    circular.self = circular

    report('unhandledrejection', circular)

    expect(sent()[0].message).toBe('[object Object]')
  })

  test('画面のパスを渡さなければ、今の URL のパス（検索条件は含まない）を送る', () => {
    window.history.pushState(null, '', '/search?q=やまだ')
    const reportWithDefaultPath = createErrorReporter(send)

    reportWithDefaultPath('error', new Error('x'))

    expect(sent()[0].path).toBe('/search')
    window.history.pushState(null, '', '/')
  })

  test('スタックトレースがなければ、null で送る', () => {
    report('unhandledrejection', '文字列')

    expect(sent()[0].stack).toBeNull()
  })

  // 境界値：サーバーの上限（文言 1000・スタック 8000・パス 200 文字）まで切って送る（超えると 400 で捨てられるため）
  test.each([
    ['文言', 1000, 1000],
    ['文言', 1001, 1000],
    ['スタックトレース', 8000, 8000],
    ['スタックトレース', 8001, 8000],
    ['パス', 200, 200],
    ['パス', 201, 200],
  ])('%sが %i 文字なら %i 文字で送る', (field, length, expected) => {
    const error = new Error('m')
    error.stack = 's'
    if (field === '文言') error.message = 'a'.repeat(length)
    if (field === 'スタックトレース') error.stack = 'a'.repeat(length)
    if (field === 'パス') path = '/' + 'a'.repeat(length - 1)

    report('error', error)

    const { message, stack, path: sentPath } = sent()[0]
    const value = field === '文言' ? message : field === 'スタックトレース' ? stack : sentPath
    expect(value).toHaveLength(expected)
  })
})

describe('送らないもの・送りすぎない仕組み', () => {
  test('通信エラー（電波が悪いなど、利用者の環境の問題）は送らない', () => {
    report('unhandledrejection', new ApiError(0, 'NETWORK_ERROR', '通信に失敗しました'))

    expect(send).not.toHaveBeenCalled()
  })

  test('通信エラー以外の API のエラーが捕まえ損ねられたら、画面の不具合なので送る', () => {
    report('unhandledrejection', new ApiError(500, 'INTERNAL_ERROR', 'サーバーでエラーが発生しました'))

    expect(sent()[0].message).toBe('サーバーでエラーが発生しました')
  })

  test('同じ種類・同じ文言のエラーは、1回のページ表示で1回だけ送る', () => {
    report('error', new Error('同じ'))
    report('error', new Error('同じ'))
    report('unhandledrejection', new Error('同じ')) // 種類が違えば別のもの

    expect(sent().map((r) => r.kind)).toEqual(['error', 'unhandledrejection'])
  })

  test(`1回のページ表示で送るのは ${MAX_REPORTS_PER_PAGE} 件まで（境界値：${MAX_REPORTS_PER_PAGE} 件目は送り、次は送らない）`, () => {
    for (let i = 1; i <= MAX_REPORTS_PER_PAGE + 1; i++) report('error', new Error(`エラー${i}`))

    expect(send).toHaveBeenCalledTimes(MAX_REPORTS_PER_PAGE)
    expect(sent().at(-1)?.message).toBe(`エラー${MAX_REPORTS_PER_PAGE}`)
  })
})

describe('ブラウザ全体のエラーを拾う（installGlobalErrorHandlers）', () => {
  let uninstall: () => void

  beforeEach(() => {
    uninstall = installGlobalErrorHandlers(report)
  })

  afterEach(() => {
    uninstall()
  })

  const errorEvent = (init: ErrorEventInit) => new ErrorEvent('error', { cancelable: true, ...init })

  test('このアプリのスクリプトで捕まえ損ねた例外を送る', () => {
    window.dispatchEvent(
      errorEvent({ error: new Error('捕まえ損ねた'), message: '捕まえ損ねた', filename: `${location.origin}/assets/index.js` }),
    )

    expect(sent()).toMatchObject([{ kind: 'error', message: '捕まえ損ねた' }])
  })

  test('例外の中身がなく文言だけのエラーも、文言を送る', () => {
    window.dispatchEvent(errorEvent({ message: 'ResizeObserver loop limit exceeded', filename: `${location.origin}/a.js` }))

    expect(sent()).toMatchObject([{ kind: 'error', message: 'ResizeObserver loop limit exceeded' }])
  })

  test('ほかのサイトのスクリプト（ブラウザ拡張など）のエラーは送らない', () => {
    window.dispatchEvent(
      errorEvent({ error: new Error('拡張のエラー'), filename: 'chrome-extension://abcdef/content.js' }),
    )

    expect(send).not.toHaveBeenCalled()
  })

  test('中身が見えないエラー（別のオリジンの「Script error.」）は送らない', () => {
    window.dispatchEvent(errorEvent({ message: 'Script error.' }))

    expect(send).not.toHaveBeenCalled()
  })

  test('捕まえ損ねた Promise の失敗を送る', () => {
    const event = new Event('unhandledrejection')
    Object.assign(event, { reason: new Error('Promise の失敗') })

    window.dispatchEvent(event)

    expect(sent()).toMatchObject([{ kind: 'unhandledrejection', message: 'Promise の失敗' }])
  })

  test('外したら、もう拾わない', () => {
    uninstall()

    // 例外の中身（error）を付けると、受け取る人がいないためテストの実行環境が本物の例外として扱うので、文言だけにする
    window.dispatchEvent(errorEvent({ message: '外したあと', filename: `${location.origin}/a.js` }))

    expect(send).not.toHaveBeenCalled()
  })
})
