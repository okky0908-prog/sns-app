// API のリクエスト・レスポンスの型。
//
// 形は手で書かず、API 仕様書（docs/openapi.yaml）から自動で作る schema.ts の型を使う。ここでは画面のコードで使う名前を付けるだけ。
// バックエンドの API が変わったら、npm run gen:api で schema.ts を作り直す。形がずれていれば型チェック（npm run build）で気づける
// （docs/api.md「フロントの型の自動生成」）
import type * as Schema from './schema'

/** ユーザーの基本情報（ログイン中のユーザー、投稿・コメントの投稿者） */
export type UserSummary = Schema.UserResponse

/** 新規登録・ログイン・再発行の結果。リフレッシュトークンは HttpOnly Cookie で届くので、ここには含まれない */
export type AuthResponse = Schema.AuthResponse

export type FieldErrorBody = Schema.FieldError

/** エラーの種類（バックエンドの ErrorCode と同じ名前）。画面の分岐はステータスや文言ではなく、この code で行う */
export type ApiErrorCode = Schema.ApiError['code']

export type ApiErrorBody = Schema.ApiError

export type SignupInput = Schema.SignupRequest

export type LoginInput = Schema.LoginRequest

export type PostImage = Schema.PostImageResponse

/** 投稿（docs/api.md「Post（投稿）」） */
export type Post = Schema.PostResponse

/** いいね・取り消し（A-40・A-41）の結果。投稿の likeCount・likedByMe をこの値で上書きする */
export type LikeState = Schema.LikeResponse

/** プロフィール（A-60） */
export type Profile = Schema.ProfileResponse

/** ユーザー一覧の1行（A-52・A-53 フォロー中・フォロワー一覧、A-70 ユーザー検索） */
export type UserListItem = Schema.UserListItemResponse

/** フォロー・フォロー解除（A-50・A-51）の結果 */
export type FollowState = Schema.FollowResponse

/** コメント（A-30 の items の各要素） */
export type Comment = Schema.CommentResponse

/** コメント投稿（A-31）の結果。投稿後のコメント数も付く */
export type CreatedComment = Schema.CreatedCommentResponse

/**
 * カーソル方式の一覧 API のレスポンス（docs/api.md「ページングのレスポンス」）。
 * 仕様書では中身の型ごとに別の名前（CursorPageResponsePostResponse など）になるので、共通の形を中身の型で受け取る
 */
export type CursorPage<T> = Omit<Schema.CursorPageResponsePostResponse, 'items'> & { items: T[] }
