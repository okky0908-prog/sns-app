# API設計

[要件定義書](./requirements.md) の詳細ドキュメント。React（フロントエンド）から Spring Boot（バックエンド）を呼び出す REST API（JSON）をまとめる。

## 共通ルール

| 項目 | 内容 |
|---|---|
| ベースURL | `/api` |
| データ形式 | JSON（画像を送る API だけ `multipart/form-data`） |
| 認証 | ログインで受け取った JWT を `Authorization: Bearer <token>` ヘッダーで送る |
| 日時 | ISO 8601 形式（例: `2026-09-30T10:15:00+09:00`） |
| JSON のキー | キャメルケース（例: `likeCount`） |
| ページング | `?page=0` から始まる。1ページ20件。レスポンスに `hasNext` を含める |

### エラーレスポンス

```json
{
  "status": 400,
  "message": "入力内容に誤りがあります",
  "errors": [
    { "field": "content", "message": "280文字以内で入力してください" }
  ]
}
```

| ステータス | 使う場面 |
|---|---|
| 400 Bad Request | 入力チェックエラー |
| 401 Unauthorized | 未ログイン、トークンが無効・期限切れ |
| 403 Forbidden | 他人の投稿・コメントを編集・削除しようとした |
| 404 Not Found | 投稿・ユーザーが存在しない |
| 409 Conflict | ユーザー名・メールアドレスが登録済み |
| 413 Payload Too Large | アップロードする画像のサイズが上限を超えた |

## API一覧

| ID | メソッド | パス | 認証 | 概要 | 機能 |
|---|---|---|---|---|---|
| A-01 | POST | `/api/auth/signup` | 不要 | 新規登録。成功したらトークンも返す | F-01 |
| A-02 | POST | `/api/auth/login` | 不要 | ログイン | F-02 |
| A-03 | GET | `/api/auth/me` | 必要 | ログイン中のユーザー情報 | F-04 |
| A-10 | GET | `/api/timeline?page=0` | 必要 | フォロー中タイムライン（自分 + フォロー中の投稿） | F-20, F-21 |
| A-15 | GET | `/api/timeline/all?page=0` | 必要 | 全体タイムライン（全ユーザーの投稿） | F-22, F-21 |
| A-11 | POST | `/api/posts` | 必要 | 投稿作成（multipart） | F-10 |
| A-12 | GET | `/api/posts/{postId}` | 必要 | 投稿詳細 | F-13 |
| A-13 | PUT | `/api/posts/{postId}` | 必要（本人のみ） | 投稿の本文を編集 | F-11 |
| A-14 | DELETE | `/api/posts/{postId}` | 必要（本人のみ） | 投稿削除 | F-12 |
| A-30 | GET | `/api/posts/{postId}/comments?page=0` | 必要 | コメント一覧（古い順） | F-31 |
| A-31 | POST | `/api/posts/{postId}/comments` | 必要 | コメント投稿 | F-30 |
| A-32 | DELETE | `/api/comments/{commentId}` | 必要（本人のみ） | コメント削除。200 で削除後のコメント数 `{ commentCount }` を返す | F-32 |
| A-40 | POST | `/api/posts/{postId}/likes` | 必要 | いいねする | F-40 |
| A-41 | DELETE | `/api/posts/{postId}/likes` | 必要 | いいねを取り消す | F-41 |
| A-50 | POST | `/api/users/{username}/follow` | 必要 | フォローする | F-50 |
| A-51 | DELETE | `/api/users/{username}/follow` | 必要 | フォロー解除 | F-51 |
| A-52 | GET | `/api/users/{username}/following?page=0` | 必要 | フォロー中一覧 | F-52 |
| A-53 | GET | `/api/users/{username}/followers?page=0` | 必要 | フォロワー一覧 | F-52 |
| A-60 | GET | `/api/users/{username}` | 必要 | プロフィール | F-60 |
| A-61 | GET | `/api/users/{username}/posts?page=0` | 必要 | そのユーザーの投稿一覧 | F-60, F-21 |
| A-62 | PUT | `/api/users/me` | 必要 | 自分のプロフィール編集（multipart） | F-61 |
| A-70 | GET | `/api/users/search?q=yama&page=0` | 必要 | ユーザー検索。`q` が空なら最近参加したユーザー | F-70, F-71 |

- ログアウト（F-03）は JWT をフロントで破棄するだけなので API はない
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

type Page<T> = {
  items: T[];
  page: number;
  hasNext: boolean;
};
```

### Post（投稿）

A-10, A-11, A-12, A-13, A-15, A-61 で返す。

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
  "page": 0,
  "hasNext": true
}
```

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
  "token": "eyJhbGciOi...",
  "user": { "id": 1, "username": "yamada", "displayName": "山田太郎", "iconUrl": null }
}
```

- ユーザー名・メールアドレスが登録済みなら 409

### A-02 ログイン

`POST /api/auth/login`

```json
// リクエスト
{ "email": "yamada@example.com", "password": "password123" }

// レスポンス 200 OK（A-01 と同じ形）
{ "token": "eyJhbGciOi...", "user": { ... } }
```

- メールアドレスかパスワードが違えば 401（どちらが違うかは返さない）

### A-11 投稿作成

`POST /api/posts`（`multipart/form-data`）

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

`GET /api/users/search?q=yama&page=0`

| パラメータ | 必須 | 説明 |
|---|---|---|
| q | | 検索キーワード。最大50文字。前後の空白と先頭の `@` はサーバー側で取り除く。空または省略なら「最近参加したユーザー」を返す |
| page | | ページ番号（0 から）。省略時は 0 |

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
  "page": 0,
  "hasNext": false
}
```

- `items` の形は A-52 / A-53 と同じ（フロントで同じ部品を使える）
- 並び順: ユーザー名が完全一致 → ユーザー名が前方一致 → それ以外。同じ順位の中ではユーザー名の昇順
- `q` が51文字以上なら 400
- 0件でもエラーにせず、`items` を空の配列で返す
