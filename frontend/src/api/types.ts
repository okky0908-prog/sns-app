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

/**
 * エラーの種類（docs/api.md「エラーレスポンス」・バックエンドの ErrorCode と同じ名前）。
 * 画面の分岐はステータスや文言ではなく、この code で行う
 */
export type ApiErrorCode =
  | 'VALIDATION_FAILED'
  | 'MALFORMED_REQUEST'
  | 'CANNOT_FOLLOW_SELF'
  | 'UNAUTHENTICATED'
  | 'INVALID_CREDENTIALS'
  | 'SESSION_EXPIRED'
  | 'FORBIDDEN'
  | 'POST_NOT_FOUND'
  | 'COMMENT_NOT_FOUND'
  | 'USER_NOT_FOUND'
  | 'RESOURCE_NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'ALREADY_REGISTERED'
  | 'PAYLOAD_TOO_LARGE'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'INTERNAL_ERROR'
  | 'IMAGE_UPLOAD_FAILED'
  | 'SERVICE_UNAVAILABLE'

export interface ApiErrorBody {
  status: number
  code: ApiErrorCode
  /** 利用者にそのまま見せてよい文言 */
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

/** 投稿（docs/api.md「Post（投稿）」） */
export interface Post {
  id: number
  content: string
  /** 画像投稿の実装までは常に空 */
  images: PostImage[]
  author: UserSummary
  likeCount: number
  /** ログイン中のユーザーがいいね済みか（ハートの色） */
  likedByMe: boolean
  commentCount: number
  /** 本文を編集した日時。null なら未編集 */
  editedAt: string | null
  createdAt: string
  /** ログイン中のユーザーの投稿か（「…」メニューを出すかどうか） */
  mine: boolean
}

/** いいね・取り消し（A-40・A-41）の結果。投稿の likeCount・likedByMe をこの値で上書きする */
export type LikeState = Pick<Post, 'likeCount' | 'likedByMe'>

/** プロフィール（A-60） */
export interface Profile extends UserSummary {
  bio: string | null
  followingCount: number
  followerCount: number
  /** ログイン中の自分がこのユーザーをフォローしているか */
  followedByMe: boolean
  /** 自分のプロフィールか（「プロフィールを編集」とフォローボタンのどちらを出すか） */
  me: boolean
}

/** ユーザー一覧の1行（A-52・A-53 フォロー中・フォロワー一覧、A-70 ユーザー検索） */
export interface UserListItem extends UserSummary {
  bio: string | null
  /** ログイン中の自分がこの人をフォローしているか（一覧を見ている相手ではなく、自分から見た状態） */
  followedByMe: boolean
  /** 自分自身か（フォローボタンを出さない） */
  me: boolean
}

/** フォロー・フォロー解除（A-50・A-51）の結果 */
export interface FollowState {
  following: boolean
  /** 相手のフォロワー数 */
  followerCount: number
}

/** コメント（A-30 の items の各要素） */
export interface Comment {
  id: number
  content: string
  author: UserSummary
  createdAt: string
  /** ログイン中のユーザーのコメントか（「削除」を出すかどうか） */
  mine: boolean
}

/** コメント投稿（A-31）の結果。投稿後のコメント数も付く */
export type CreatedComment = Comment & { commentCount: number }

/** カーソル方式の一覧 API のレスポンス（docs/api.md「ページングのレスポンス」） */
export interface CursorPage<T> {
  items: T[]
  /** 続きを取るときに cursor に渡す値。続きがなければ null */
  nextCursor: string | null
  hasNext: boolean
}
