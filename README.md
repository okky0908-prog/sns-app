# X風SNSアプリ

X（旧Twitter）のような、タイムライン形式のテキスト中心のSNS風Webアプリケーション。スクール課題として、要件定義〜設計〜実装を一人で経験することを主目的に開発する。

想定利用者は受講生・個人（複数ユーザー）で、ログインして投稿し、互いにいいね・コメント・フォローし合う。X/Twitter との差別化として、**インプレッション数を表示せず、リツイート機能を持たない**。詳細は[要件定義書](docs/requirements.md)を参照。

## 主な機能（MVP）

- 新規登録・ログイン（JWT認証）
- テキスト（280文字）＋画像（4枚まで、S3に保存）の投稿、編集・削除
- タイムライン（フォロー中・全体）。各投稿にいいね数・コメント数を表示
- コメント、いいね
- フォロー・フォロワー、プロフィールの表示・編集
- ユーザー検索（`@ユーザー名`・表示名）。投稿やコメントの名前からプロフィールへ移動してフォロー

詳細は[機能要件](docs/features.md)・[画面仕様・画面遷移・ユースケース](docs/screens.md)を参照。

## 技術スタック

前回のタスク管理アプリ（`github-demo`）と同じ技術は、同じバージョンを使う。

| レイヤー | 技術 |
|---|---|
| フロントエンド | React 19 + TypeScript 7 + Vite 8（React Router） |
| バックエンド | Java 25 + Spring Boot 4.1 (Gradle Kotlin DSL)、Spring Security + JWT |
| データベース | PostgreSQL 17（MyBatis + Flyway） |
| 画像保存 | Amazon S3 + CloudFront |
| ローカル環境 | Docker Compose（PostgreSQL・LocalStack コンテナ） |
| インフラ（暫定案） | AWS（ALB + EC2 + RDS）、Terraform |

各コンポーネントの詳細バージョン・採用理由は[技術スタック](docs/tech-stack.md)を参照。

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [要件定義書](docs/requirements.md) | 全体の概要。各章から下の詳細ドキュメントへリンクする |
| [機能要件](docs/features.md) | 機能一覧（F-01〜F-71）と業務ルール |
| [画面仕様・画面遷移・ユースケース](docs/screens.md) | 画面一覧・共通部品・各画面のワイヤーフレーム・画面遷移図・ユースケース |
| [非機能要件](docs/non-functional-requirements.md) | 性能・可用性・セキュリティ・保守性・ユーザビリティ・互換性・データ永続性 |
| [データ構造・ER図](docs/database.md) | ER図・テーブル定義・インデックス・集計 SQL・マイグレーション |
| [API設計](docs/api.md) | REST API の一覧とリクエスト・レスポンス |
| [技術スタック](docs/tech-stack.md) | 使う技術とバージョン |
| [インフラ構成](docs/infrastructure.md) | AWS の構成図（暫定案） |
| [機能定義書](docs/feature-specs/README.md) | 機能分類ごとの処理の流れ・エラー時の動き・受け入れ条件（8本） |
| [モックアップ実装計画書](docs/mockup-plan.md) | 本実装前に作成した静的モックアップの内容と動作確認結果 |

ER図・画面遷移図・シーケンス図は Mermaid で書いている。VSCode では拡張機能「Markdown Preview Mermaid Support」を入れるとプレビューで表示できる。

## ディレクトリ構成（予定）

```
.
├── backend/    # Spring Boot バックエンド（REST API）
├── frontend/   # React + Vite フロントエンド（SPA）
├── docs/       # 要件定義・設計ドキュメント
├── mockup/     # 実装前に作成した静的HTML/CSS/JSモックアップ
├── infra/      # AWS構築用の Terraform コード・デプロイスクリプト
└── docker-compose.yml   # ローカルの PostgreSQL・LocalStack 起動用
```

## モックアップ

本実装の前に、HTML/CSS/JavaScript だけで動く静的モックを作成した（データはメモリのみ。リロードで初期データに戻る）。

- `mockup/index.html` をブラウザで直接開く（または `cd mockup && python3 -m http.server 8000` で `http://localhost:8000`）
- ログイン画面のデモ用アカウント（例：`yamada@example.com` / `password123`）をクリックしてログインする

内容と動作確認結果は[モックアップ実装計画書](docs/mockup-plan.md)を参照。

## セットアップ・起動方法

現在は、ユーザー登録・ログイン・ログアウトまで実装済み（バックエンドの API と、フロントエンドのログイン・新規登録画面）。認証はアクセストークン（15分）＋リフレッシュトークン（14日、HttpOnly Cookie）の方式。ログイン後の画面は「ログイン成功」を表示する仮の画面で、タイムラインなどはこれから実装する。

固定ポート（`backend: 8080` / `frontend: 5173` / `postgres: 5432`）で起動する。ポートが競合したときの対処など詳しい手順は[.claude/skills/run/SKILL.md](.claude/skills/run/SKILL.md)を参照。

### 前提条件

- Java 25（例：`brew install openjdk@25`。keg-only のため `JAVA_HOME` の指定が必要）
- Node.js 24系（`frontend/.nvmrc`）
- Docker（Docker Compose）

### 1. 環境変数ファイルを用意する

```bash
cp .env.example .env
# .env の JWT_SECRET に、32バイト以上のランダムな文字列を入れる
openssl rand -base64 48
```

`.env` は docker compose とバックエンドの両方が読む（Git には入れない）。

### 2. PostgreSQL を起動する

```bash
docker compose up -d postgres
```

### 3. バックエンドを起動する

```bash
cd backend
JAVA_HOME=$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home ./gradlew bootRun
```

起動時に Flyway がテーブルを作成する。動作確認の例：

```bash
curl -s -H 'Content-Type: application/json' \
  -d '{"username":"yamada","displayName":"山田太郎","email":"yamada@example.com","password":"password123"}' \
  http://localhost:8080/api/auth/signup
```

### 4. フロントエンドを起動する（別のターミナル）

```bash
cd frontend
npm ci        # 初回だけ
npm run dev
```

http://localhost:5173 を開くとログイン画面が表示される。「新規登録はこちら」からアカウントを作ると、そのままログインして「ログイン成功」の画面に移る。`/api` へのリクエストは Vite のプロキシでバックエンド（8080）に中継される。

### テスト・整形チェック

```bash
cd backend
JAVA_HOME=$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home ./gradlew check   # Spotless の整形チェック + テスト
JAVA_HOME=$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home ./gradlew spotlessApply   # 整形を自動で直す
```

テストはローカルの PostgreSQL（`docker compose up -d postgres`）を使う。各テストは終了時にロールバックされ、データは残らない。

フロントエンドの型チェック・ビルドと lint：

```bash
cd frontend
npm run build   # TypeScript の型チェック + ビルド
npm run lint    # oxlint
```
