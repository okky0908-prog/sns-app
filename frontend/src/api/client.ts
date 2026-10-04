import type { ApiErrorBody, AuthResponse, FieldErrorBody, LoginInput, SignupInput, UserSummary } from './types'

const TOKEN_KEY = 'sns-app.token'

// ===== トークン（localStorage に保存する。docs/feature-specs/01_auth.md） =====

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function saveToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

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

// ログインが必要な API で 401 が返ったときに呼ぶ処理（AuthProvider がログアウト処理を登録する）
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

// ===== 共通の呼び出し =====

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** true ならトークンを付ける。401 が返ったら unauthorizedHandler を呼ぶ */
  auth?: boolean
}

async function apiFetch<T>(path: string, { method = 'GET', body, auth = false }: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const token = auth ? getToken() : null
  if (token) {
    headers.Authorization = `Bearer ${token}`
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
    if (res.status === 401 && auth) {
      unauthorizedHandler?.()
    }
    throw new ApiError(res.status, errorBody?.message ?? NETWORK_ERROR_MESSAGE, errorBody?.errors ?? [])
  }
  return (await res.json()) as T
}

// ===== 認証 API（docs/api.md の A-01〜A-03） =====

export function signup(input: SignupInput): Promise<AuthResponse> {
  return apiFetch('/api/auth/signup', { method: 'POST', body: input })
}

export function login(input: LoginInput): Promise<AuthResponse> {
  return apiFetch('/api/auth/login', { method: 'POST', body: input })
}

export function fetchMe(): Promise<UserSummary> {
  return apiFetch('/api/auth/me', { auth: true })
}
