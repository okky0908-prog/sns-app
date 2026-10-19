import { describe, expect, test } from 'vitest'
import { countChars, formatRelativeTime } from './format'

describe('countChars：見た目の文字数（サーバーの TextLength と同じ数え方）', () => {
  test('英数字・日本語は1文字ずつ数える', () => {
    expect(countChars('abc')).toBe(3)
    expect(countChars('あいう')).toBe(3)
  })

  test('絵文字は1文字と数える（JavaScript の length では2になる）', () => {
    expect('🎉'.length).toBe(2)
    expect(countChars('🎉')).toBe(1)
  })

  test('改行も1文字と数える', () => {
    expect(countChars('a\nb')).toBe(3)
  })

  test('空文字は0文字', () => {
    expect(countChars('')).toBe(0)
  })

  // 家族の絵文字などは、複数の絵文字をつないで1つに見せている。サーバー（Java の codePointCount）も同じく
  // つないだ数で数えるので、画面とサーバーで数がずれないことを優先して、見た目の1文字ではなく5文字と数える
  test('つないで1つに見せる絵文字は、つないだ数で数える（サーバーと同じ）', () => {
    expect(countChars('👨‍👩‍👧')).toBe(5)
  })
})

describe('formatRelativeTime：投稿日時の表示（docs/feature-specs/02_post.md）', () => {
  const now = new Date('2026-10-20T12:00:00+09:00').getTime()
  const before = (ms: number) => new Date(now - ms).toISOString()
  const MINUTE = 60 * 1000
  const HOUR = 60 * MINUTE

  test('1分未満は「1分前」（0分前とは出さない）', () => {
    expect(formatRelativeTime(before(0), now)).toBe('1分前')
    expect(formatRelativeTime(before(59 * 1000), now)).toBe('1分前')
  })

  test('1時間未満は「◯分前」', () => {
    expect(formatRelativeTime(before(5 * MINUTE), now)).toBe('5分前')
    expect(formatRelativeTime(before(HOUR - 1), now)).toBe('59分前')
  })

  test('1時間ちょうどから24時間未満は「◯時間前」', () => {
    expect(formatRelativeTime(before(HOUR), now)).toBe('1時間前')
    expect(formatRelativeTime(before(24 * HOUR - 1), now)).toBe('23時間前')
  })

  test('24時間ちょうどからは日付（日本時間）', () => {
    expect(formatRelativeTime(before(24 * HOUR), now)).toBe('2026/10/19')
    // UTC では 10/18 だが、日本時間では 10/19 の 00:30
    expect(formatRelativeTime('2026-10-18T15:30:00Z', now)).toBe('2026/10/19')
  })

  // サーバーと端末の時計が少しずれていて、投稿日時が「未来」になっても、おかしな表示にしない
  test('未来の日時は「1分前」にする', () => {
    expect(formatRelativeTime(before(-30 * 1000), now)).toBe('1分前')
  })
})
