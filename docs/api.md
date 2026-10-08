# API設計

[要件定義書](./requirements.md) の詳細ドキュメント。React（フロントエンド）から Spring Boot（バックエンド）を呼び出す REST API（JSON）をまとめる。

## 共通ルール

| 項目 | 内容 |
|---|---|
| ベースURL | `/api` |
| データ形式 | JSON（画像を送る API だけ `multipart/form-data`） |
| 認証 | ログイン・新規登録・再発行で受け取ったアクセストークン（JWT、15分）を `Authorization: Bearer <token>` ヘッダーで送る。リフレッシュトークン（14日）は HttpOnly Cookie で届き、ブラウザが `/api/auth` の下にだけ自動で送る（[機能定義書：認証](feature-specs/01_auth.md)） |
| 日時 | ISO 8601 形式（例: `2026-09-30T10:15:00+09:00`） |
| JSON のキー | キャメルケース（例: `likeCount`） |
| ページング | カーソル方式。1回20件。最初は `cursor` を付けずに呼び、続きはレスポンスの `nextCursor` をそのまま `?cursor=` に渡す（[ページングのレスポンス](#ページングのレスポンス)） |

### エラーレスポンス

エラーはすべて（存在しない URL・サーバー内部のエラーも含めて）次の形で返す。

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "入力内容に誤りがあります",
  "errors": [
    { "field": "content", "message": "280文字以内で入力してください" }
  ]
}
```

| 項目 | 内容 |
|---|---|
| `status` | HTTP ステータスと同じ値 |
| `code` | エラーの種類（下の表）。**画面側の分岐はステータスや文言ではなく `code` で行う**（文言を変えても画面の動きが変わらないように） |
| `message` | 利用者にそのまま見せてよい文言。内部の情報（例外の内容・SQL など）は入れない |
| `errors` | 項目ごとの入力エラー。なければ空の配列 |

| ステータス | `code` | 使う場面 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 入力チェックエラー（`errors` に項目ごとの内容が入る） |
| 400 | `MALFORMED_REQUEST` | JSON が壊れているなど、リクエストを読めない |
| 401 | `UNAUTHENTICATED` | アクセストークンがない・無効・期限切れ。画面側は A-04 で再発行して1回だけやり直す |
| 401 | `INVALID_CREDENTIALS` | ログインでメールアドレスまたはパスワードが違う |
| 401 | `SESSION_EXPIRED` | リフレッシュトークンがない・無効・期限切れ（再発行できない。もう一度ログイン） |
| 403 | `FORBIDDEN` | 他人の投稿・コメントを編集・削除しようとした |
| 404 | `POST_NOT_FOUND` | 投稿が存在しない（削除された） |
| 404 | `COMMENT_NOT_FOUND` | コメントが存在しない（削除された） |
| 404 | `RESOURCE_NOT_FOUND` | 存在しない URL |
| 405 | `METHOD_NOT_ALLOWED` | その URL で使えないメソッド |
| 409 | `ALREADY_REGISTERED` | ユーザー名・メールアドレスが登録済み（どの項目かは `errors` に入る） |
| 413 | `PAYLOAD_TOO_LARGE` | アップロードする画像のサイズが上限を超えた |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type が違う（JSON の API に JSON 以外を送った など） |
| 500 | `INTERNAL_ERROR` | 想定していないエラー（バグなど）。原因はサーバーのログにだけ残す |
| 503 | `SERVICE_UNAVAILABLE` | DB につながらないなど、一時的に処理できない |

- 種類はバックエンドの `ErrorCode`（列挙型）で一元管理する。増やすときは `ErrorCode`・この表・フロントの `ApiErrorCode` の3か所に同じ名前を追加する。一度使い始めた名前は変えない
- 機能を追加して「〇〇が見つからない」などの種類が増えるときは、`USER_NOT_FOUND` のように個別の `code` を追加する
- 500・503 のときは、サーバーのログにリクエスト（メソッド・URL）とスタックトレースを出す
- 画面側では、通信そのものに失敗したときは `NETWORK_ERROR`、API の形式でないエラーが返ったときは `UNKNOWN` として扱う（フロントの `ApiError.code`）

## API一覧

| ID | メソッド | パス | 認証 | 概要 | 機能 |
|---|---|---|---|---|---|
| A-01 | POST | `/api/auth/signup` | 不要 | 新規登録。成功したらトークンも返す | F-01 |
| A-02 | POST | `/api/auth/login` | 不要 | ログイン | F-02 |
| A-03 | GET | `/api/auth/me` | 必要 | ログイン中のユーザー情報 | F-04 |
| A-04 | POST | `/api/auth/refresh` | リフレッシュトークン（Cookie） | アクセストークンの再発行。リフレッシュトークンも新しいものに交換する | F-04 |
| A-05 | POST | `/api/auth/logout` | リフレッシュトークン（Cookie） | ログアウト。リフレッシュトークンを無効にする | F-03 |
| A-10 | GET | `/api/timeline?cursor=` | 必要 | フォロー中タイムライン（自分 + フォロー中の投稿） | F-20, F-21 |
| A-15 | GET | `/api/timeline/all?cursor=` | 必要 | 全体タイムライン（全ユーザーの投稿） | F-22, F-21 |
| A-16 | GET | `/api/timeline/new-count?since=` | 必要 | フォロー中タイムラインの新しい投稿の件数 | F-24 |
| A-17 | GET | `/api/timeline/all/new-count?since=` | 必要 | 全体タイムラインの新しい投稿の件数 | F-24 |
| A-11 | POST | `/api/posts` | 必要 | 投稿作成（multipart）。**現在はテキストのみのため JSON `{ content }`**。画像投稿の実装時に multipart に変える | F-10 |
| A-12 | GET | `/api/posts/{postId}` | 必要 | 投稿詳細 | F-13 |
| A-13 | PUT | `/api/posts/{postId}` | 必要（本人のみ） | 投稿の本文を編集 | F-11 |
| A-14 | DELETE | `/api/posts/{postId}` | 必要（本人のみ） | 投稿削除 | F-12 |
| A-30 | GET | `/api/posts/{postId}/comments?cursor=` | 必要 | コメント一覧（古い順） | F-31 |
| A-31 | POST | `/api/posts/{postId}/comments` | 必要 | コメント投稿 | F-30 |
| A-32 | DELETE | `/api/comments/{commentId}` | 必要（本人のみ） | コメント削除。200 で削除後のコメント数 `{ commentCount }` を返す | F-32 |
| A-40 | POST | `/api/posts/{postId}/likes` | 必要 | いいねする | F-40 |
| A-41 | DELETE | `/api/posts/{postId}/likes` | 必要 | いいねを取り消す | F-41 |
| A-50 | POST | `/api/users/{username}/follow` | 必要 | フォローする | F-50 |
| A-51 | DELETE | `/api/users/{username}/follow` | 必要 | フォロー解除 | F-51 |
| A-52 | GET | `/api/users/{username}/following?cursor=` | 必要 | フォロー中一覧 | F-52 |
| A-53 | GET | `/api/users/{username}/followers?cursor=` | 必要 | フォロワー一覧 | F-52 |
| A-60 | GET | `/api/users/{username}` | 必要 | プロフィール | F-60 |
| A-61 | GET | `/api/users/{username}/posts?cursor=` | 必要 | そのユーザーの投稿一覧 | F-60, F-21 |
| A-62 | PUT | `/api/users/me` | 必要 | 自分のプロフィール編集（multipart） | F-61 |
| A-70 | GET | `/api/users/search?q=yama&cursor=` | 必要 | ユーザー検索。`q` が空なら最近参加したユーザー | F-70, F-71 |

- 「認証：必要」はアクセストークンが必要という意味。期限切れなどで 401 が返ったら、画面側は A-04 で再発行して1回だけやり直す
- ALB のヘルスチェック用に `GET /api/health`（認証不要、200 を返すだけ）を用意する（[インフラ構成](infrastructure.md)）
- 各機能の画面の動き・エラー時の動きは [機能定義書](feature-specs/README.md) を参照
- 画像のアップロードは Spring Boot が受け取って S3 に保存する。ブラウザが画像を表示するときは、API が返す `url`（CloudFront の URL）から直接取得し、Spring Boot は経由しない

### 画像 URL の組み立て

DB には S3 のオブジェクトキー（例: `posts/2026/09/3f2a...jpg`）だけを保存し、API で返すときに「配信元のURL + キー」で URL を組み立てる。
配信元のURL は `application.yml` の設定値（例: `app.image-base-url`）で環境ごとに切り替える。

| 環境 | 配信元のURL（例） |
|---|---|
| 開発（LocalStack） | `http://localhost:4566/sns-app-images` |
| 本番（CloudFront） | `https://dxxxxxxxx.cloudfront.net` |

- 本番の S3 バケットは公開せず、CloudFront からだけ読めるようにする（OAC を使う）

## 共通のデータ形式

フロントエンドでは、API のレスポンスと同じ形の TypeScript の型を定義して使う。

```ts
type UserSummary = {
  id: number;
  username: string;      // @の後ろ
  displayName: string;
  iconUrl: string | null;
};

type Post = {
  id: number;
  content: string;
  images: { url: string; sortOrder: number }[];
  author: UserSummary;
  likeCount: number;
  commentCount: number;
  likedByMe: boolean;
  editedAt: string | null; // null なら未編集
  createdAt: string;       // ISO 8601
  mine: boolean;           // 自分の投稿か
};

type UserListItem = UserSummary & {
  bio: string | null;
  followedByMe: boolean;
  me: boolean;             // 自分自身か
};

type CursorPage<T> = {
  items: T[];
  nextCursor: string | null; // 続きを読むときに ?cursor= に渡す。続きがなければ null
  hasNext: boolean;
};
```

### Post（投稿）

A-10, A-11, A-12, A-13, A-15, A-61 で返す。

> **現在の実装：** `images` は画像投稿の実装までは常に空の配列。

```json
{
  "id": 101,
  "content": "今日はSpring Bootの勉強をしました。",
  "images": [
    { "url": "https://dxxxxxxxx.cloudfront.net/posts/2026/09/3f2a...jpg", "sortOrder": 1 }
  ],
  "author": {
    "id": 1,
    "username": "yamada",
    "displayName": "山田太郎",
    "iconUrl": "https://dxxxxxxxx.cloudfront.net/icons/8c1d...png"
  },
  "likeCount": 12,
  "commentCount": 3,
  "likedByMe": true,
  "editedAt": "2026-09-30T10:20:00+09:00",
  "createdAt": "2026-09-30T10:15:00+09:00",
  "mine": true
}
```

| キー | 説明 |
|---|---|
| likeCount | いいね数 |
| commentCount | コメント数 |
| likedByMe | ログイン中の自分がいいね済みか。ハートの色に使う |
| editedAt | 編集した日時。`null` なら未編集。`null` でなければ「編集済み」を表示 |
| mine | 自分の投稿か。`…` メニュー（編集・削除）を出すかどうかに使う |

インプレッション数やリツイート数のキーは持たない。

### ページングのレスポンス

一覧系の API（A-10, A-15, A-30, A-52, A-53, A-61, A-70）は次の形で返す。

```json
{
  "items": [ /* Post などの配列 */ ],
  "nextCursor": "MTc1OTE5NDkwMDoxMjM0NTY3ODk6MTAx",
  "hasNext": true
}
```

- 続きは `nextCursor` をそのまま `?cursor=` に付けて呼ぶ。中身は画面側で解釈しない（サーバーが「どこまで返したか」を入れた文字列）
- ページ番号（OFFSET）方式と違い、読んでいる途中で新しい投稿が増えても、同じものが2回返ったり抜けたりしない
- `cursor` の形式が違えば 400
- タイムライン（A-10・A-15）のカーソルには、最後に返した投稿の投稿日時と ID が入っている（「それより古いもの」を次に返す）

## 各 API の詳細

### A-01 新規登録

`POST /api/auth/signup`

```json
// リクエスト
{
  "username": "yamada",
  "displayName": "山田太郎",
  "email": "yamada@example.com",
  "password": "password123"
}

// レスポンス 201 Created
{
  "accessToken": "eyJhbGciOi...",
  "user": { "id": 1, "username": "yamada", "displayName": "山田太郎", "iconUrl": null }
}
```

```
Set-Cookie: refresh_token=Xb3k...; Path=/api/auth; Max-Age=1209600; HttpOnly; SameSite=Strict（本番は Secure も付ける）
```

- リフレッシュトークンは JSON には入れず、Cookie でだけ返す
- ユーザー名・メールアドレスが登録済みなら 409

### A-02 ログイン

`POST /api/auth/login`

```json
// リクエスト
{ "email": "yamada@example.com", "password": "password123" }

// レスポンス 200 OK（A-01 と同じ形。リフレッシュトークンの Cookie も返す）
{ "accessToken": "eyJhbGciOi...", "user": { ... } }
```

- メールアドレスかパスワードが違えば 401（どちらが違うかは返さない）

### A-04 アクセストークンの再発行

`POST /api/auth/refresh`（リクエストの本文はなし。リフレッシュトークンの Cookie をブラウザが自動で付ける）

```json
// レスポンス 200 OK（A-01 と同じ形。新しいリフレッシュトークンの Cookie も返す）
{ "accessToken": "eyJhbGciOi...", "user": { ... } }

// レスポンス 401（Cookie がない・知らないトークン・期限切れ・使用済みのトークン）
{ "status": 401, "message": "ログインの有効期限が切れました。もう一度ログインしてください", "errors": [] }
```

- 使ったリフレッシュトークンは無効にし、新しいものを Cookie で返す（ローテーション）
- 使用済みのリフレッシュトークンがもう一度使われたら、そのユーザーのリフレッシュトークンをすべて無効にして 401 を返す
- ただし、交換から10秒以内の再利用（2つのタブが同時に再発行した等）は、同時のリクエストとみなして 200 で新しいトークンを返す
- 画面を開いたときにも呼び、ログイン状態を復元する（レスポンスの `user` を使う）

### A-05 ログアウト

`POST /api/auth/logout`（リクエストの本文はなし）

- レスポンス 204 No Content。`Set-Cookie: refresh_token=; Max-Age=0` で Cookie を消す
- リフレッシュトークンを無効にする。Cookie がない（すでにログアウト済み）ときも 204

### A-11 投稿作成

`POST /api/posts`（`multipart/form-data`）

> **現在の実装（テキストのみ）：** 画像投稿を実装するまでは、JSON `{ "content": "本文" }` で受け取る。本文は前後の空白・改行を除いて1〜280文字（必須）。

| パート名 | 型 | 必須 | 説明 |
|---|---|---|---|
| content | テキスト | △ | 本文。280文字まで |
| images | ファイル（複数可） | △ | 4枚まで。jpg / png / gif、1枚5MBまで |

- content と images のどちらか一方は必須
- レスポンス 201 Created。作成した Post を返す

### A-13 投稿編集

`PUT /api/posts/{postId}`

```json
// リクエスト
{ "content": "今日はSpring Bootの勉強をしました。（追記）" }
```

- 本文だけ変更できる。`edited_at` に現在日時を入れる
- 他人の投稿なら 403
- レスポンス 200 OK。更新後の Post を返す

### A-14 投稿削除

`DELETE /api/posts/{postId}`

- 他人の投稿なら 403
- レスポンス 204 No Content
- 画像ファイルも S3 から削除する（DB のコミット後に行う。詳しくは [データ構造・ER図「参照整合性・カスケード削除について」](database.md#参照整合性カスケード削除について)）

### A-16 / A-17 新しい投稿の件数

`GET /api/timeline/new-count?since=123`（フォロー中）／`GET /api/timeline/all/new-count?since=123`（全体）

| パラメータ | 必須 | 説明 |
|---|---|---|
| since | ○ | 画面が最後に A-10 / A-15 の1ページ目で取った、一番新しい投稿の ID。0件だったときは 0 |

```json
// レスポンス 200 OK
{ "count": 3 }
```

- `since` より ID が大きい（＝後から投稿された）投稿の件数を返す。投稿 ID は登録順に振られる
- 自分の投稿は数えない（投稿した時点で画面に出ているため）。フォロー中（A-16）は、フォロー中の人の投稿だけを数える
- 100件で数えるのをやめる（画面では99件を超えたら「99+」と出すので、全部は数えない）
- 画面は60秒ごとに呼ぶ。編集・削除は数えない（取り直したときに反映される）
- `since` がない・数字でなければ 400

### A-30 コメント一覧

`GET /api/posts/{postId}/comments?cursor=`

```json
// レスポンス 200 OK（ページングのレスポンス）
{
  "items": [
    {
      "id": 55,
      "content": "わかりやすいです！",
      "author": { "id": 2, "username": "sato", "displayName": "佐藤花子", "iconUrl": null },
      "createdAt": "2026-09-30T10:30:00+09:00",
      "mine": false
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

- 古い順に20件。続きは `nextCursor` を `?cursor=` に渡す（カーソルには最後に返したコメントの日時と ID が入っていて、それより新しいものを返す）
- `mine` は自分のコメントか（「削除」を出すかどうか）
- 投稿が存在しなければ 404（`POST_NOT_FOUND`）

### A-31 コメント投稿

`POST /api/posts/{postId}/comments`

```json
// リクエスト
{ "content": "わかりやすいです！" }

// レスポンス 201 Created
{
  "id": 55,
  "content": "わかりやすいです！",
  "author": { "id": 2, "username": "sato", "displayName": "佐藤花子", "iconUrl": null },
  "createdAt": "2026-09-30T10:30:00+09:00",
  "mine": true,
  "commentCount": 4
}
```

- 画面でコメント数をすぐ更新できるように、投稿後のコメント数（`commentCount`）も返す
- 本文は前後の空白・改行を除いて1〜280文字（数え方は投稿と同じ）。違反は 400（`VALIDATION_FAILED`）。投稿が存在しなければ 404（`POST_NOT_FOUND`）

### A-32 コメント削除

`DELETE /api/comments/{commentId}`

```json
// レスポンス 200 OK
{ "commentCount": 3 }
```

- 削除できるのはコメントした本人だけ。他人のコメントは 403（`FORBIDDEN`。投稿者でも消せない）
- コメントがなければ 404（`COMMENT_NOT_FOUND`）
- 画面でコメント数をすぐ更新できるように、削除後のコメント数を返す

### A-40 / A-41 いいね・いいね取り消し

`POST /api/posts/{postId}/likes` / `DELETE /api/posts/{postId}/likes`

```json
// レスポンス 200 OK（どちらも同じ形）
{ "likeCount": 13, "likedByMe": true }
```

- **何回呼んでも結果が同じ**になるようにする
  - すでにいいね済みで POST しても、エラーにせず今の状態を返す
  - いいねしていない状態で DELETE しても、エラーにせず今の状態を返す
- ボタンの連打や通信の再送があっても、数がずれたりエラー画面になったりしないようにするため

### A-50 / A-51 フォロー・フォロー解除

`POST /api/users/{username}/follow` / `DELETE /api/users/{username}/follow`

```json
// レスポンス 200 OK
{ "following": true, "followerCount": 9 }
```

- 自分自身をフォローしようとしたら 400
- いいねと同じく、何回呼んでも結果が同じになるようにする

### A-60 プロフィール

`GET /api/users/{username}`

```json
{
  "id": 1,
  "username": "yamada",
  "displayName": "山田太郎",
  "bio": "Javaを勉強中の受講生です。",
  "iconUrl": "https://dxxxxxxxx.cloudfront.net/icons/8c1d...png",
  "followingCount": 12,
  "followerCount": 8,
  "followedByMe": false,
  "me": false
}
```

| キー | 説明 |
|---|---|
| followedByMe | ログイン中の自分がこのユーザーをフォローしているか |
| me | 自分のプロフィールか。「プロフィールを編集」と「フォローする」のどちらを出すかに使う |

### A-52 / A-53 フォロー中・フォロワー一覧

`items` の中身は次の形。

```json
{
  "id": 2,
  "username": "sato",
  "displayName": "佐藤花子",
  "bio": "Reactが好きです",
  "iconUrl": null,
  "followedByMe": true,
  "me": false
}
```

### A-62 プロフィール編集

`PUT /api/users/me`（`multipart/form-data`）

| パート名 | 型 | 必須 | 説明 |
|---|---|---|---|
| displayName | テキスト | ○ | 1〜50文字 |
| bio | テキスト | | 160文字まで |
| icon | ファイル | | 送ったときだけアイコンを差し替える。jpg / png / gif、5MBまで |

- レスポンス 200 OK。A-60 と同じ形で更新後のプロフィールを返す

### A-70 ユーザー検索

`GET /api/users/search?q=yama&cursor=`

| パラメータ | 必須 | 説明 |
|---|---|---|
| q | | 検索キーワード。最大50文字。前後の空白と先頭の `@` はサーバー側で取り除く。空または省略なら「最近参加したユーザー」を返す |
| cursor | | 前回のレスポンスの `nextCursor`。省略すると先頭から |

```json
// レスポンス 200 OK
{
  "items": [
    {
      "id": 1,
      "username": "yamada",
      "displayName": "山田太郎",
      "bio": "Javaを勉強中の受講生です。",
      "iconUrl": "https://dxxxxxxxx.cloudfront.net/icons/8c1d...png",
      "followedByMe": true,
      "me": false
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

- `items` の形は A-52 / A-53 と同じ（フロントで同じ部品を使える）
- 並び順: ユーザー名が完全一致 → ユーザー名が前方一致 → それ以外。同じ順位の中ではユーザー名の昇順
- `q` が51文字以上なら 400
- 0件でもエラーにせず、`items` を空の配列で返す
