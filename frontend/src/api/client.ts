import type {
  ApiErrorBody,
  ApiErrorCode,
  AuthResponse,
  Comment,
  CreatedComment,
  CursorPage,
  FieldErrorBody,
  FollowState,
  LikeState,
  LoginInput,
  Post,
  Profile,
  UserListItem,
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

/**
 * エラーの種類。API が返す code に加えて、画面側だけで使うもの
 * - NETWORK_ERROR：通信そのものに失敗した（オフライン・サーバーが止まっている など）
 * - UNKNOWN：API の形式でないエラーが返った（途中のプロキシのエラーページ など）
 */
export type ErrorCode = ApiErrorCode | 'NETWORK_ERROR' | 'UNKNOWN'

/** API がエラーを返したとき、または通信に失敗したときに投げる。分岐は status ではなく code で行う */
export class ApiError extends Error {
  /** HTTP ステータス。通信に失敗したときは 0 */
  readonly status: number
  readonly code: ErrorCode
  readonly fieldErrors: FieldErrorBody[]

  constructor(status: number, code: ErrorCode, message: string, fieldErrors: FieldErrorBody[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

export const NETWORK_ERROR_MESSAGE = '通信に失敗しました。時間をおいてもう一度お試しください'

/** 利用者に見せるエラーの文言。API の文言があればそれを、なければ（想定外の例外など）通信エラーの文言を返す */
export function errorMessage(err: unknown): string {
  return err instanceof ApiError ? err.message : NETWORK_ERROR_MESSAGE
}

/** 指定した種類のエラーか */
export function isApiError(err: unknown, code: ErrorCode): err is ApiError {
  return err instanceof ApiError && err.code === code
}

// ログインが必要な API で、再発行もできずに 401 になったときに呼ぶ処理（AuthProvider がログアウト処理を登録する）
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

// ===== 共通の呼び出し =====

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** JSON にして送る値。FormData なら multipart/form-data のまま送る（画像のアップロード） */
  body?: unknown
  /** true ならアクセストークンを付ける。401 が返ったら再発行して1回だけやり直す */
  auth?: boolean
}

/** 1回だけリクエストを送る（Cookie は同じオリジンなのでブラウザが自動で付ける） */
async function send<T>(path: string, { method = 'GET', body, auth = false }: RequestOptions): Promise<T> {
  const headers: Record<string, string> = {}
  const isForm = body instanceof FormData
  // FormData のときは Content-Type を付けない（ブラウザが区切り文字つきの multipart/form-data を付ける）
  if (body !== undefined && !isForm) {
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
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE)
  }

  if (!res.ok) {
    const errorBody = (await res.json().catch(() => null)) as Partial<ApiErrorBody> | null
    if (!errorBody?.code || !errorBody.message) {
      throw new ApiError(res.status, 'UNKNOWN', NETWORK_ERROR_MESSAGE)
    }
    throw new ApiError(res.status, errorBody.code, errorBody.message, errorBody.errors ?? [])
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
}

async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  try {
    return await send<T>(path, options)
  } catch (err) {
    if (!options.auth || !isApiError(err, 'UNAUTHENTICATED')) {
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
    if (isApiError(err, 'UNAUTHENTICATED')) {
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

/** A-11 投稿作成（multipart/form-data）。本文と画像（4枚まで、選んだ順）のどちらか一方は必要 */
export function createPost(content: string, images: File[] = []): Promise<Post> {
  const form = new FormData()
  form.append('content', content)
  for (const image of images) form.append('images', image)
  return apiFetch('/api/posts', { method: 'POST', body: form, auth: true })
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

// ===== いいね API（docs/api.md の A-40・A-41）。何度呼んでも同じ結果になる =====

/** A-40 いいねする */
export function likePost(postId: number): Promise<LikeState> {
  return apiFetch(`/api/posts/${postId}/likes`, { method: 'POST', auth: true })
}

/** A-41 いいねを取り消す */
export function unlikePost(postId: number): Promise<LikeState> {
  return apiFetch(`/api/posts/${postId}/likes`, { method: 'DELETE', auth: true })
}

// ===== コメント API（docs/api.md の A-30〜A-32） =====

/** A-30 コメント一覧（古い順に20件ずつ）。続きは前回の nextCursor を渡す */
export function fetchComments(postId: number, cursor: string | null = null): Promise<CursorPage<Comment>> {
  const path = `/api/posts/${postId}/comments`
  return apiFetch(cursor ? `${path}?cursor=${encodeURIComponent(cursor)}` : path, { auth: true })
}

/** A-31 コメント投稿。投稿後のコメント数も返る */
export function createComment(postId: number, content: string): Promise<CreatedComment> {
  return apiFetch(`/api/posts/${postId}/comments`, { method: 'POST', body: { content }, auth: true })
}

/** A-32 コメント削除（本人だけ）。削除後のコメント数が返る */
export function deleteComment(commentId: number): Promise<{ commentCount: number }> {
  return apiFetch(`/api/comments/${commentId}`, { method: 'DELETE', auth: true })
}

// ===== プロフィール・フォロー API（docs/api.md の A-50・A-51・A-60・A-61） =====

/** A-60 プロフィール。ユーザー名は大文字・小文字を区別しない */
export function fetchProfile(username: string): Promise<Profile> {
  return apiFetch(`/api/users/${encodeURIComponent(username)}`, { auth: true })
}

/** A-61 そのユーザーの投稿一覧（新しい順に20件ずつ）。続きは前回の nextCursor を渡す */
export function fetchUserPosts(username: string, cursor: string | null = null): Promise<CursorPage<Post>> {
  const path = `/api/users/${encodeURIComponent(username)}/posts`
  return apiFetch(cursor ? `${path}?cursor=${encodeURIComponent(cursor)}` : path, { auth: true })
}

/** A-50 フォローする（何度呼んでも同じ結果） */
export function followUser(username: string): Promise<FollowState> {
  return apiFetch(`/api/users/${encodeURIComponent(username)}/follow`, { method: 'POST', auth: true })
}

/** A-51 フォローを解除する（何度呼んでも同じ結果） */
export function unfollowUser(username: string): Promise<FollowState> {
  return apiFetch(`/api/users/${encodeURIComponent(username)}/follow`, { method: 'DELETE', auth: true })
}

export type FollowListKind = 'following' | 'followers'

/** A-52 フォロー中一覧 / A-53 フォロワー一覧（フォローした日時の新しい順に20件ずつ）。続きは前回の nextCursor を渡す */
export function fetchFollowList(
  username: string,
  kind: FollowListKind,
  cursor: string | null = null,
): Promise<CursorPage<UserListItem>> {
  const path = `/api/users/${encodeURIComponent(username)}/${kind}`
  return apiFetch(cursor ? `${path}?cursor=${encodeURIComponent(cursor)}` : path, { auth: true })
}

export interface ProfileUpdateInput {
  displayName: string
  bio: string
  /** 新しいアイコン画像。選んでいなければ null（アイコンは今のまま） */
  icon: File | null
}

/** A-62 自分のプロフィールの編集（multipart/form-data）。更新後のプロフィールが返る */
export function updateProfile(input: ProfileUpdateInput): Promise<Profile> {
  const form = new FormData()
  form.append('displayName', input.displayName)
  form.append('bio', input.bio)
  if (input.icon) form.append('icon', input.icon)
  return apiFetch('/api/users/me', { method: 'PUT', body: form, auth: true })
}

// ===== ユーザー検索 API（docs/api.md の A-70） =====

/** A-70 ユーザー検索（20件ずつ）。q が空なら最近参加したユーザー。続きは前回の nextCursor を渡す */
export function searchUsers(q: string, cursor: string | null = null): Promise<CursorPage<UserListItem>> {
  const params = new URLSearchParams({ q })
  if (cursor) params.set('cursor', cursor)
  return apiFetch(`/api/users/search?${params}`, { auth: true })
}
