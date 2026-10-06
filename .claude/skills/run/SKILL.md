---
name: run
description: このリポジトリ（Spring Boot バックエンド + Vite/React フロントエンド + PostgreSQL + LocalStack）の開発サーバーを起動する。ポート競合時は必ず占有プロセスを停止し、指定の固定ポートで起動し直す（別ポートへの退避は禁止）。
---

# アプリの起動手順

## 固定ポート（変更禁止）

| コンポーネント | ポート | 根拠 |
|---|---|---|
| PostgreSQL | `5432` | `.env` の `POSTGRES_PORT`（`docker-compose.yml`） |
| LocalStack（S3 の代わり） | `4566` | LocalStack のデフォルト（`docker-compose.yml`） |
| バックエンド（Spring Boot） | `8080` | `backend/src/main/resources/application.yml` に `server.port` の指定なし＝Spring Bootデフォルト |
| フロントエンド（Vite） | `5173` | `frontend/vite.config.ts` に `server.port` の指定なし＝Viteデフォルト |

- フロントエンドは `/api` へのリクエストを Vite のプロキシでバックエンド（`localhost:8080`）へ中継する（本番の ALB のパス振り分けの代わり。[インフラ構成](../../../docs/infrastructure.md)参照）
- 画像は LocalStack の S3 バケットに保存され、`http://localhost:4566/<バケット名>/...` の URL で表示される

## 起動コマンド

```bash
# 0. 初回だけ：.env を用意する（JWT_SECRET に openssl rand -base64 48 の結果を入れる）
cp .env.example .env

# 1. DB と S3（LocalStack）
docker compose up -d postgres localstack   # LocalStack を追加するまでは postgres だけ

# 2. バックエンド（リポジトリルートから）。Java 25 は Homebrew の openjdk@25（keg-only のため JAVA_HOME を指定）
cd backend && JAVA_HOME=$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home ./gradlew bootRun

# 3. フロントエンド（別ターミナル、リポジトリルートから）。初回は npm ci で依存関係を入れる
cd frontend && npm run dev
```

- 現在の実装状況：PostgreSQL・バックエンド（認証・投稿・タイムラインの API）・フロントエンド（ログイン・新規登録・タイムライン〈無限スクロール・新しい投稿のお知らせ〉・投稿詳細の画面）。LocalStack は画像投稿の実装時に追加する。まだないものは起動しなくてよい
- バックエンドより先に LocalStack を起動しておくこと（画像のアップロード先の S3 バケットが必要なため）
- S3 バケットは LocalStack の起動時に初期化スクリプトで作成する。バケットがない場合は、LocalStack が起動しきっているか（下の起動確認）を確認する

## ポート競合時の対応（必須ルール）

**空いている別のポートに逃がして起動することは禁止。** 必ず上記の固定ポートで起動できる状態にしてから起動すること。

1. 対象ポートを使っているプロセスを確認する

   ```bash
   lsof -ti tcp:8080   # バックエンド
   lsof -ti tcp:5173   # フロントエンド
   lsof -ti tcp:5432   # PostgreSQL
   lsof -ti tcp:4566   # LocalStack
   ```

2. 見つかったプロセスがこのプロジェクトの古い起動プロセス（前回のセッションで停止し忘れたbootRun/vite/コンテナなど）であれば停止する

   ```bash
   lsof -ti tcp:8080 | xargs kill
   ```

   - 停止対象がこのプロジェクトと無関係なプロセスに見える場合（PIDやプロセス名から判断がつかない、他の重要そうなサービスが動いている等）は、killする前に必ずユーザーに確認する。
     - 特に 5432・4566 は、別プロジェクト（`github-demo`・`bookshelf-app` など）の PostgreSQL・LocalStack のコンテナが使っていることがある。その場合は kill せず、`docker ps` でどのプロジェクトのコンテナかを確認してユーザーに報告する
   - `kill` で終了しない場合のみ `kill -9` を検討する。

3. ポートが空いたことを確認してから、同じ固定ポートで再度起動する。

## 起動確認

- LocalStack: `curl -sf http://localhost:4566/_localstack/health`（`"s3": "running"` または `"available"` になっていること）
- バックエンド: `curl -sf http://localhost:8080/api/health`（Actuator の health を `/api/health` で公開している。[API設計](../../../docs/api.md)参照）
- フロントエンド: `curl -sf http://localhost:5173` またはブラウザでアクセスして表示を確認
- フロントエンド経由の API: `curl -sf http://localhost:5173/api/health`（Vite のプロキシが効いていること）
