// 表示用の小さな関数（mockup/script.js の同名の関数を移したもの）

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** 見た目の文字数で数える（絵文字も1文字。サーバーの codePointCount と同じ） */
export function countChars(text: string): number {
  return [...text].length
}

/** 1時間以内は「◯分前」、24時間以内は「◯時間前」、それより前は「2026/09/30」（docs/feature-specs/02_post.md） */
export function formatRelativeTime(iso: string, now: number = Date.now()): string {
  const time = new Date(iso).getTime()
  const diff = now - time
  if (diff < HOUR) return `${Math.max(1, Math.floor(diff / MINUTE))}分前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)}時間前`
  const d = new Date(time)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`
}
