import type {
  ApiErrorBody,
  AuthResponse,
  CursorPage,
  FieldErrorBody,
  LoginInput,
  Post,
  SignupInput,
  UserSummary,
} from './types'

/*
 * 認証の方式（docs/feature-specs/01_auth.md）
 * - アクセストークン（有効期限15分）：このファイルの変数（メモリ）にだけ持つ。localStorage には保存しない（XSS で盗まれないように）
 * - リフレッシュトークン（有効期限14日）：サーバーが HttpOnly Cookie で渡す。JavaScript からは読めず、ブラウザが自動で送る
 * - ページを開き直したときや、アクセストークンの期限が切れたときは、/api/auth/refresh でアクセストークンを再発行する
 */

let accessToken: string | null = null

// 以前の版（アクセストークンだけの方式）で localStorage に保存していたトークンを消しておく
localStorage.removeItem('sns-app.token')

// ===== エラー =====

/** API がエラーを返したとき、または通信に失敗したときに投げる */
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: FieldErrorBody[]

  constructor(status: number, message: string, fieldErrors: FieldErrorBody[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

export const NETWORK_ERROR_MESSAGE = '通信に失敗しました。時間をおいてもう一度お試しください'

// ログインが必要な API で、再発行もできずに 401 になったときに呼ぶ処理（AuthProvider がログアウト処理を登録する）
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

// ===== 共通の呼び出し =====

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** true ならアクセストークンを付ける。401 が返ったら再発行して1回だけやり直す */
  auth?: boolean
}

/** 1回だけリクエストを送る（Cookie は同じオリジンなのでブラウザが自動で付ける） */
async function send<T>(path: string, { method = 'GET', body, auth = false }: RequestOptions): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (auth && accessToken) {
    headers.Authorization = `Bearer ${accessToken}`
  }

  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, NETWORK_ERROR_MESSAGE)
  }

  if (!res.ok) {
    const errorBody = (await res.json().catch(() => null)) as ApiErrorBody | null
    throw new ApiError(res.status, errorBody?.message ?? NETWORK_ERROR_MESSAGE, errorBody?.errors ?? [])
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
}

async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  try {
    return await send<T>(path, options)
  } catch (err) {
    if (!options.auth || !(err instanceof ApiError) || err.status !== 401) {
      throw err
    }
  }
  // アクセストークンの期限切れなど。再発行して1回だけやり直す
  try {
    await refresh()
  } catch (refreshError) {
    accessToken = null
    unauthorizedHandler?.()
    throw refreshError
  }
  try {
    return await send<T>(path, options)
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      accessToken = null
      unauthorizedHandler?.()
    }
    throw err
  }
}

// ===== 認証 API（docs/api.md の A-01〜A-05） =====

function keepAccessToken(res: AuthResponse): AuthResponse {
  accessToken = res.accessToken
  return res
}

export async function signup(input: SignupInput): Promise<AuthResponse> {
  return keepAccessToken(await apiFetch('/api/auth/signup', { method: 'POST', body: input }))
}

export async function login(input: LoginInput): Promise<AuthResponse> {
  return keepAccessToken(await apiFetch('/api/auth/login', { method: 'POST', body: input }))
}

let refreshing: Promise<AuthResponse> | null = null

/**
 * リフレッシュトークン（Cookie）でアクセストークンを再発行する。
 * リフレッシュトークンは一度使うと新しいものに交換されるので、同時に呼ばれても再発行は1回にまとめる。
 */
export function refresh(): Promise<AuthResponse> {
  refreshing ??= send<AuthResponse>('/api/auth/refresh', { method: 'POST' })
    .then(keepAccessToken)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

export function fetchMe(): Promise<UserSummary> {
  return apiFetch('/api/auth/me', { auth: true })
}

/** ログアウト。サーバーでリフレッシュトークンを無効にし、手元のアクセストークンも捨てる */
export async function logout(): Promise<void> {
  try {
    await send<void>('/api/auth/logout', { method: 'POST' })
  } finally {
    accessToken = null
  }
}

// ===== 投稿・タイムライン API（docs/api.md の A-10〜A-15）。すべてアクセストークンが必要 =====

export type TimelineTab = 'following' | 'all'

/** A-10 フォロー中タイムライン / A-15 全体タイムライン（20件ずつ）。続きは前回の nextCursor を渡す */
export function fetchTimeline(tab: TimelineTab, cursor: string | null = null): Promise<CursorPage<Post>> {
  const path = tab === 'all' ? '/api/timeline/all' : '/api/timeline'
  return apiFetch(cursor ? `${path}?cursor=${encodeURIComponent(cursor)}` : path, { auth: true })
}

/** A-11 投稿作成（今はテキストのみ） */
export function createPost(content: string): Promise<Post> {
  return apiFetch('/api/posts', { method: 'POST', body: { content }, auth: true })
}

/** A-12 投稿詳細 */
export function fetchPost(postId: number): Promise<Post> {
  return apiFetch(`/api/posts/${postId}`, { auth: true })
}

/** A-13 投稿編集（本文のみ。本人だけ） */
export function updatePost(postId: number, content: string): Promise<Post> {
  return apiFetch(`/api/posts/${postId}`, { method: 'PUT', body: { content }, auth: true })
}

/** A-14 投稿削除（本人だけ） */
export function deletePost(postId: number): Promise<void> {
  return apiFetch(`/api/posts/${postId}`, { method: 'DELETE', auth: true })
}

/** A-16 / A-17 画面が最後に取った一番新しい投稿（sinceId）より後に増えた、他人の投稿の件数（100件で打ち切り） */
export async function fetchNewPostCount(tab: TimelineTab, sinceId: number): Promise<number> {
  const path = tab === 'all' ? '/api/timeline/all/new-count' : '/api/timeline/new-count'
  const res = await apiFetch<{ count: number }>(`${path}?since=${sinceId}`, { auth: true })
  return res.count
}
