// API のレスポンスの型（docs/api.md・バックエンドの UserResponse / AuthResponse / ApiError と同じ形）

export interface UserSummary {
  id: number
  username: string
  displayName: string
  iconUrl: string | null
}

export interface AuthResponse {
  /** アクセストークン（有効期限15分）。リフレッシュトークンは HttpOnly Cookie で届くので、ここには含まれない */
  accessToken: string
  user: UserSummary
}

export interface FieldErrorBody {
  field: string
  message: string
}

export interface ApiErrorBody {
  status: number
  message: string
  errors: FieldErrorBody[]
}

export interface SignupInput {
  username: string
  displayName: string
  email: string
  password: string
}

export interface LoginInput {
  email: string
  password: string
}

export interface PostImage {
  url: string
  sortOrder: number
}

/** 投稿（docs/api.md「Post（投稿）」）。いいね数・コメント数は、それぞれの機能の実装時に追加する */
export interface Post {
  id: number
  content: string
  /** 画像投稿の実装までは常に空 */
  images: PostImage[]
  author: UserSummary
  /** 本文を編集した日時。null なら未編集 */
  editedAt: string | null
  createdAt: string
  /** ログイン中のユーザーの投稿か（「…」メニューを出すかどうか） */
  mine: boolean
}

/** 一覧 API のレスポンス（docs/api.md「ページングのレスポンス」） */
export interface Page<T> {
  items: T[]
  page: number
  hasNext: boolean
}
