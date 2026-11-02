import { isApiError, reportClientError } from '../api/client'
import type { ClientErrorReport } from '../api/types'

/*
 * 画面で起きたエラーを拾って、サーバーのログに送る（docs/observability.md「3.1 ログの種類」の画面のエラー）。
 *
 * - 拾うもの：画面の表示中のエラー（React・React Router）、捕まえ損ねた例外（error）・Promise の失敗（unhandledrejection）
 * - 送らないもの：通信エラー（利用者の環境の問題）、ほかのサイトのスクリプト（ブラウザ拡張など）のエラー
 * - 送りすぎない：同じ種類・同じ文言は1回のページ表示で1回だけ、全部で MAX_REPORTS_PER_PAGE 件まで
 *   （同じ不具合が何度も起きても、サーバーとログをあふれさせない）
 */

export type ErrorKind = ClientErrorReport['kind']
export type Reporter = (kind: ErrorKind, error: unknown, componentStack?: string) => void

export const MAX_REPORTS_PER_PAGE = 10

/** サーバーの上限（ClientErrorReport）。超えると 400 で捨てられるので、切ってから送る */
const MESSAGE_MAX = 1000
const STACK_MAX = 8000
const PATH_MAX = 200

/**
 * エラーを送る関数を作る。送った件数と送ったものは、作った関数ごとに覚える（ページを読み込み直すと最初から）
 *
 * @param send 送る先（テストでは偽物）
 * @param getPath 今の画面のパス（URL の検索条件は含めない）
 */
export function createErrorReporter(
  send: (report: ClientErrorReport) => Promise<void>,
  getPath: () => string = () => window.location.pathname,
): Reporter {
  const sentKeys = new Set<string>()
  return (kind, error, componentStack) => {
    if (isApiError(error, 'NETWORK_ERROR')) return
    const message = messageOf(error)
    const key = `${kind}:${message}`
    if (sentKeys.has(key) || sentKeys.size >= MAX_REPORTS_PER_PAGE) return
    sentKeys.add(key)

    const stack = [error instanceof Error ? error.stack : undefined, componentStack].filter(Boolean).join('')
    void send({
      kind,
      message: message.slice(0, MESSAGE_MAX),
      stack: stack ? stack.slice(0, STACK_MAX) : null,
      path: getPath().slice(0, PATH_MAX),
    })
  }
}

/** エラーを文言にする。Promise の失敗の理由は Error とは限らない（文字列・オブジェクトなど） */
function messageOf(error: unknown): string {
  if (error instanceof Error) return error.message || error.name
  if (typeof error === 'string') return error
  try {
    return JSON.stringify(error) ?? String(error)
  } catch {
    return String(error)
  }
}

/** ブラウザ全体で捕まえ損ねたエラーを拾う。返した関数を呼ぶと外す */
export function installGlobalErrorHandlers(report: Reporter, target: Window = window): () => void {
  const onError = (event: ErrorEvent) => {
    // ほかのサイトのスクリプト（ブラウザ拡張など）は、このアプリの不具合ではない
    if (event.filename && !event.filename.startsWith(target.location.origin)) return
    // 別のオリジンのスクリプトのエラーは、ブラウザが中身を隠して「Script error.」だけにする。調べようがないので送らない
    if (!event.error && event.message === 'Script error.') return
    report('error', event.error ?? event.message)
  }
  const onRejection = (event: PromiseRejectionEvent) => report('unhandledrejection', event.reason)
  target.addEventListener('error', onError)
  target.addEventListener('unhandledrejection', onRejection)
  return () => {
    target.removeEventListener('error', onError)
    target.removeEventListener('unhandledrejection', onRejection)
  }
}

/** アプリ全体で使う、エラーを送る関数（main.tsx と画面のエラーの表示で使う） */
export const reportError: Reporter = createErrorReporter(reportClientError)
