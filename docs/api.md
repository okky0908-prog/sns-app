# API設計

[要件定義書](./requirements.md) の詳細ドキュメント。React（フロントエンド）から Spring Boot（バックエンド）を呼び出す REST API（JSON）をまとめる。

## API 仕様書（OpenAPI）

各 API のリクエスト・レスポンスの形（項目・型・必須か・null になりうるか・文字数）は、コードから自動で作る API 仕様書（OpenAPI 3.1）にまとまっている。このファイルには形を書かず、形からは分からない決まりと動きだけを書く（同じことを2か所に手で書くと、片方だけ直し忘れてずれるため）。

| 見たいもの | 場所 |
|---|---|
| 仕様書のファイル | [openapi.yaml](./openapi.yaml)（リポジトリで管理。API の変更は PR の差分で読める） |
| ブラウザで見る・試す | バックエンドを起動して http://localhost:8080/swagger-ui.html （ローカル開発でだけ開く。本番は閉じる） |

- このファイル（api.md）は、共通のルール・エラーの code の使い分け・画面との対応など「考え方」を、openapi.yaml は各 API の「形」を受け持つ
- 仕様書は springdoc-openapi が、コントローラー・record と、その Javadoc から作る。自動で載らないこのアプリの決まり（JWT 認証・ログインなしで呼べる API・エラー形式・`@TextLength` の文字数など）は `OpenApiConfig` で補っている
- Swagger UI でログインが必要な API を試すときは、ログイン（A-02）か新規登録（A-01）の `accessToken` を右上の「Authorize」に入れる

### 仕様書の更新と CI

- `./gradlew check`（または `test`）を実行すると、`OpenApiSpecTest` が `docs/openapi.yaml` を書き出す。API を変えたら、テストを実行して openapi.yaml も一緒にコミットする
- CI は、テストのあとに openapi.yaml に差分が出ないかを確かめる。コミットし忘れると CI が落ちる
- API を追加・変更するときの決まり
  - コントローラーのメソッドの Javadoc は「A-xx 名前。説明」の形で書く。最初の「。」まで（括弧の中の「。」は除く）が Swagger UI の一覧に出る概要になる
  - コントローラーを増やしたら `@Tag(name = "分類名")` を付け、`OpenApiConfig` の `TAG_ORDER` にも分類名を足す
  - `ResponseEntity` でステータスを決める API は、`@ApiResponse(responseCode = "201", ...)` のように仕様書用にステータスを書く（`@ResponseStatus` を使う API は不要）
  - レスポンスの record で null を返すことがある項目には `@Schema(nullable = true)` を付ける（付けないと、フロントの型が null を許さない）。レスポンスの項目は、`OpenApiConfig` がすべて必須（required）にする

### フロントの型の自動生成

フロントの API の型は手で書かず、openapi.yaml から [openapi-typescript](https://openapi-ts.dev/) で `frontend/src/api/schema.ts` を作る。バックエンドとフロントで項目名や null の扱いがずれていれば、フロントの型チェック（`npm run build`）で気づける。

```
コントローラー・record ──(OpenApiSpecTest)──▶ docs/openapi.yaml ──(npm run gen:api)──▶ frontend/src/api/schema.ts
             CI：openapi.yaml に差分がないか          CI：schema.ts に差分がないか
```

- 画面のコードは `schema.ts` を直接使わず、`src/api/types.ts` の名前（`Post`・`Profile` など）で使う。types.ts は schema.ts の型に名前を付けるだけで、形は書かない
- API を変えたら、`./gradlew test`（openapi.yaml を作り直す）→ `npm run gen:api`（schema.ts を作り直す）→ `npm run build`（型のずれを確かめる）の順に実行し、openapi.yaml と schema.ts を一緒にコミットする
- openapi-typescript は TypeScript 5 が前提で、フロントの TypeScript 7 では動かない。そのため、型を作るツールだけ `frontend/tools/openapi-typescript/`（専用の package.json と lockfile）に分けている。初回は `npm ci --prefix tools/openapi-typescript` でインストールする

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
| 400 | `CANNOT_FOLLOW_SELF` | 自分自身をフォローしようとした |
| 401 | `UNAUTHENTICATED` | アクセストークンがない・無効・期限切れ。画面側は A-04 で再発行して1回だけやり直す |
| 401 | `INVALID_CREDENTIALS` | ログインでメールアドレスまたはパスワードが違う |
| 401 | `SESSION_EXPIRED` | リフレッシュトークンがない・無効・期限切れ（再発行できない。もう一度ログイン） |
| 403 | `FORBIDDEN` | 他人の投稿・コメントを編集・削除しようとした |
| 404 | `POST_NOT_FOUND` | 投稿が存在しない（削除された） |
| 404 | `COMMENT_NOT_FOUND` | コメントが存在しない（削除された） |
| 404 | `USER_NOT_FOUND` | ユーザーが存在しない |
| 404 | `RESOURCE_NOT_FOUND` | 存在しない URL |
| 405 | `METHOD_NOT_ALLOWED` | その URL で使えないメソッド |
| 409 | `ALREADY_REGISTERED` | ユーザー名・メールアドレスが登録済み（どの項目かは `errors` に入る） |
| 413 | `PAYLOAD_TOO_LARGE` | アップロードする画像のサイズが上限を超えた |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type が違う（JSON の API に JSON 以外を送った など） |
| 500 | `INTERNAL_ERROR` | 想定していないエラー（バグなど）。原因はサーバーのログにだけ残す |
| 500 | `IMAGE_UPLOAD_FAILED` | 画像を S3 に保存できなかった（ほかの項目も更新しない） |
| 503 | `SERVICE_UNAVAILABLE` | DB につながらないなど、一時的に処理できない |

- 種類はバックエンドの `ErrorCode`（列挙型）で一元管理する。増やすときは `ErrorCode` とこの表に同じ名前を追加する。フロントの `ApiErrorCode` は、API 仕様書から自動で作られる（[フロントの型の自動生成](#フロントの型の自動生成)）。一度使い始めた名前は変えない
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
| A-11 | POST | `/api/posts` | 必要 | 投稿作成（multipart。本文と画像4枚まで） | F-10 |
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
配信元のURL は `application.yml` の設定値 `app.storage.image-base-url`（環境変数 `IMAGE_BASE_URL`）で環境ごとに切り替える。

| 環境 | 配信元のURL（例） |
|---|---|
| 開発（LocalStack） | `http://localhost:4566/sns-app-images` |
| 本番（CloudFront） | `https://dxxxxxxxx.cloudfront.net` |

- 本番の S3 バケットは公開せず、CloudFront からだけ読めるようにする（OAC を使う）

## 共通のデータ形式

レスポンスの形は、API 仕様書の `components.schemas`（Swagger UI の下の「Schemas」）にある。フロントでは、そこから自動で作る `frontend/src/api/schema.ts` の型を、`src/api/types.ts` の名前で使う（[フロントの型の自動生成](#フロントの型の自動生成)）。

| 画面のコードでの名前（types.ts） | 仕様書のスキーマ | 使う API |
|---|---|---|
| `UserSummary` | `UserResponse` | ログイン中のユーザー（A-01〜A-04）、投稿・コメントの投稿者（`author`） |
| `Post` | `PostResponse` | A-10〜A-13・A-15・A-61 |
| `Comment` / `CreatedComment` | `CommentResponse` / `CreatedCommentResponse` | A-30 / A-31 |
| `Profile` | `ProfileResponse` | A-60・A-62 |
| `UserListItem` | `UserListItemResponse` | A-52・A-53・A-70 |
| `CursorPage<T>` | `CursorPageResponse〇〇`（中身の型ごとに別の名前） | 一覧系の API（下の「ページングのレスポンス」） |

- `iconUrl` は、アイコンを設定していなければ `null`（画面は表示名の頭文字を出す）。投稿者・コメントした人・一覧・プロフィール・ログイン中のユーザーのどれでも同じ
- null になりうる項目は、仕様書では型に `null` が入っている（`iconUrl`・`bio`・`editedAt`・`nextCursor`）。それ以外の項目は、レスポンスに必ず入る

### Post（投稿）

- 画面の表示に使う値（`likedByMe` でハートの色、`editedAt` が null でなければ「編集済み」、`mine` で `…` メニューを出す）もサーバーが返す。画面側で計算しない
- インプレッション数やリツイート数のキーは持たない（X との差別化。[要件定義書](./requirements.md)）

### ページングのレスポンス

一覧系の API（A-10, A-15, A-30, A-52, A-53, A-61, A-70）は、`items`（中身の配列）・`nextCursor`・`hasNext` の形で返す。1回20件。

- 続きは `nextCursor` をそのまま `?cursor=` に付けて呼ぶ。中身は画面側で解釈しない（サーバーが「どこまで返したか」を入れた文字列）。続きがなければ `null`
- ページ番号（OFFSET）方式と違い、読んでいる途中で新しい投稿が増えても、同じものが2回返ったり抜けたりしない
- `cursor` の形式が違えば 400
- タイムライン（A-10・A-15）のカーソルには、最後に返した投稿の投稿日時と ID が入っている（「それより古いもの」を次に返す）

## 各 API の詳細

リクエスト・レスポンスの形とステータスコードは、API 仕様書（Swagger UI では API ごとの欄）を見る。ここには、形からは分からない動き（エラーになる条件・並び順・何回呼んでも同じ結果になること など）を書く。

### A-01 新規登録

- 登録後はそのままログイン状態にするため、アクセストークンも返す
- リフレッシュトークンは JSON には入れず、Cookie でだけ返す：`Set-Cookie: refresh_token=...; Path=/api/auth; Max-Age=1209600（14日）; HttpOnly; SameSite=Strict`（本番は `Secure` も付ける）
- ユーザー名・メールアドレスが登録済みなら 409（`ALREADY_REGISTERED`。どの項目かは `errors` に入る）
- 入力のチェック内容とメッセージは [認証の機能定義書](feature-specs/01_auth.md) を参照

### A-02 ログイン

- A-01 と同じく、アクセストークンを JSON で、リフレッシュトークンを Cookie で返す
- メールアドレスかパスワードが違えば 401（`INVALID_CREDENTIALS`。どちらが違うかは返さない）

### A-04 アクセストークンの再発行

- リクエストの本文はなし。リフレッシュトークンの Cookie をブラウザが自動で付ける
- 使ったリフレッシュトークンは無効にし、新しいものを Cookie で返す（ローテーション）
- Cookie がない・知らないトークン・期限切れ・使用済みのトークンなら 401（`SESSION_EXPIRED`）。画面はログイン画面に戻す
- 使用済みのリフレッシュトークンがもう一度使われたら、そのユーザーのリフレッシュトークンをすべて無効にして 401 を返す
- ただし、交換から10秒以内の再利用（2つのタブが同時に再発行した等）は、同時のリクエストとみなして 200 で新しいトークンを返す
- 画面を開いたときにも呼び、ログイン状態を復元する（レスポンスの `user` を使う）

### A-05 ログアウト

- リクエストの本文はなし。リフレッシュトークンを無効にし、`Set-Cookie: refresh_token=; Max-Age=0` で Cookie を消す
- Cookie がない（すでにログアウト済み）ときもエラーにせず 204

### A-11 投稿作成

- `multipart/form-data` で、本文（`content`）と画像（`images`）を送る。どちらか一方は必須（どちらもなければ 400「本文を入力するか、画像を選択してください」）
- 本文は前後の空白・改行を除いて280文字まで。改行はブラウザがフォームで送る `\r\n` を `\n` にそろえる
- 画像は4枚まで。jpg / png / gif、1枚5MBまで。形式はファイルの中身（先頭のバイト列）で判定する。違反は 400（`field` は `images`）
- 画像は S3 の `posts/{年}/{月}/{UUID}.{拡張子}`（年月は UTC）に保存してから DB に保存する。DB の保存が失敗したら保存した画像を削除する。S3 に保存できなければ 500（`IMAGE_UPLOAD_FAILED`）で、投稿も作らない
- 作成した Post を返す（`images` は選んだ順。`sortOrder` は 1〜4）

### A-13 投稿編集

- 本文だけ変更できる（画像の追加・削除はできない）。`edited_at` に現在日時を入れる
- 画像のある投稿は本文を空にしてもよい。画像のない投稿を空にすると 400「画像のない投稿は本文を空にできません」
- 他人の投稿なら 403

### A-14 投稿削除

- 他人の投稿なら 403
- 画像ファイルも S3 から削除する（DB のコミット後に行う。詳しくは [データ構造・ER図「参照整合性・カスケード削除について」](database.md#参照整合性カスケード削除について)）

### A-16 / A-17 新しい投稿の件数

- `since` には、画面が最後に A-10 / A-15 の1ページ目で取った、一番新しい投稿の ID を渡す（0件だったときは 0）。`since` がない・数字でなければ 400
- `since` より ID が大きい（＝後から投稿された）投稿の件数を返す。投稿 ID は登録順に振られる
- 自分の投稿は数えない（投稿した時点で画面に出ているため）。フォロー中（A-16）は、フォロー中の人の投稿だけを数える
- 100件で数えるのをやめる（画面では99件を超えたら「99+」と出すので、全部は数えない）
- 画面は60秒ごとに呼ぶ。編集・削除は数えない（取り直したときに反映される）

### A-30 コメント一覧

- 古い順に20件。続きはカーソル方式（カーソルには最後に返したコメントの日時と ID が入っていて、それより新しいものを返す）
- `mine` は自分のコメントか（「削除」を出すかどうか）
- 投稿が存在しなければ 404（`POST_NOT_FOUND`）

### A-31 コメント投稿

- 画面でコメント数をすぐ更新できるように、投稿後のコメント数（`commentCount`）も返す
- 本文は前後の空白・改行を除いて1〜280文字（数え方は投稿と同じ）。違反は 400（`VALIDATION_FAILED`）。投稿が存在しなければ 404（`POST_NOT_FOUND`）

### A-32 コメント削除

- 削除できるのはコメントした本人だけ。他人のコメントは 403（`FORBIDDEN`。投稿者でも消せない）
- コメントがなければ 404（`COMMENT_NOT_FOUND`）
- 画面でコメント数をすぐ更新できるように、削除後のコメント数を返す

### A-40 / A-41 いいね・いいね取り消し

- **何回呼んでも結果が同じ**になるようにする
  - すでにいいね済みで POST しても、エラーにせず今の状態を返す
  - いいねしていない状態で DELETE しても、エラーにせず今の状態を返す
- ボタンの連打や通信の再送があっても、数がずれたりエラー画面になったりしないようにするため
- 画面は押した直後に表示を変え、返ってきた値（ほかの人のいいねも反映した数）で上書きする

### A-50 / A-51 フォロー・フォロー解除

- 自分自身をフォローしようとしたら 400（`CANNOT_FOLLOW_SELF`）。ユーザーがいなければ 404（`USER_NOT_FOUND`）
- いいねと同じく、何回呼んでも結果が同じになるようにする
- `{username}` は大文字・小文字を区別しない（A-60・A-61 も同じ）

### A-60 プロフィール

- `me` は自分のプロフィールか。「プロフィールを編集」と「フォローする」のどちらを出すかに使う
- ユーザーがいなければ 404（`USER_NOT_FOUND`）

### A-61 そのユーザーの投稿一覧

- 中身はタイムライン（A-10・A-15）と同じ Post の一覧。新しい順に20件、続きはカーソル方式
- ユーザーがいなければ 404（`USER_NOT_FOUND`）

### A-52 / A-53 フォロー中・フォロワー一覧

- フォローした日時（フォロワー一覧ならフォローされた日時）の新しい順に20件。続きはカーソル方式（カーソルにはフォローした日時と follows の ID が入っている）
- `followedByMe` は、一覧を見ている相手ではなく **ログイン中の自分** がその人をフォローしているか。`me` は自分自身の行か（フォローボタンを出さない）
- ユーザーがいなければ 404（`USER_NOT_FOUND`）。`{username}` は大文字・小文字を区別しない

### A-62 プロフィール編集

- `multipart/form-data` で、表示名（`displayName`、必須、1〜50文字）・自己紹介（`bio`、160文字まで）・アイコン画像（`icon`）を送る。アイコンは送ったときだけ差し替える
- A-60 と同じ形で更新後のプロフィールを返す
- 表示名・自己紹介は前後の空白・改行を除いて数える（絵文字も1文字）。改行はブラウザがフォームで送る `\r\n` を `\n` にそろえてから数える。自己紹介を空にすると `null` になる
- アイコンは jpg / png / gif、5MBまで。形式は、ファイル名や Content-Type ではなく **ファイルの中身（先頭のバイト列）** で判定する。保存先は S3 の `icons/{UUID}.{拡張子}`
- 入力エラーは 400（`VALIDATION_FAILED`。`errors` の `field` は `displayName`・`bio`・`icon`）。S3 に保存できなければ 500（`IMAGE_UPLOAD_FAILED`）で、表示名・自己紹介も更新しない
- アイコンを差し替えたら、DB の更新がコミットされたあとに古いアイコンを S3 から削除する（失敗してもログに残すだけ）。DB の更新が失敗したら、保存したばかりの新しいアイコンを削除する
- 1回のリクエストの上限は 25MB（`spring.servlet.multipart`）。超えると 413（`PAYLOAD_TOO_LARGE`）

### A-70 ユーザー検索

- `q`（検索キーワード）は最大50文字。前後の空白と先頭の `@` はサーバー側で取り除いてから探す（文字数も取り除いてから数える）。51文字以上なら 400（`VALIDATION_FAILED`、`field` は `q`）
- `q` が空または省略なら、「最近参加したユーザー」を新しい順に返す
- `items` の形は A-52 / A-53 と同じ（フロントで同じ部品を使える）
- 並び順: ユーザー名が完全一致 → ユーザー名が前方一致 → それ以外。同じ順位の中ではユーザー名の昇順
- 0件でもエラーにせず、`items` を空の配列で返す
- `%` `_` `\` は普通の文字として探す（LIKE のエスケープ）
- 続きはカーソル方式。キーワードありのカーソルには「一致の度合い・ユーザー名（小文字）」、キーワードなし（最近参加したユーザー）のカーソルには「登録日時・ID」が入っている。形式が違えば 400
- URL の形が A-60（`/api/users/{username}`）と同じなので、`search` というユーザー名は新規登録で使えない（[認証の機能定義書](feature-specs/01_auth.md)）
