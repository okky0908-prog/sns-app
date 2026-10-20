# X風SNSアプリ

X（旧Twitter）のような、タイムライン形式のテキスト中心のSNS風Webアプリケーション。スクール課題として、要件定義〜設計〜実装を一人で経験することを主目的に開発する。

想定利用者は受講生・個人（複数ユーザー）で、ログインして投稿し、互いにいいね・コメント・フォローし合う。X/Twitter との差別化として、**インプレッション数を表示せず、リツイート機能を持たない**。詳細は[要件定義書](docs/requirements.md)を参照。

## 主な機能（MVP）

- 新規登録・ログイン（JWT認証）
- テキスト（280文字）＋画像（4枚まで、S3に保存）の投稿、編集・削除
- タイムライン（フォロー中・全体）。下までスクロールすると続きを自動で読み込み（無限スクロール）、新しい投稿は「↑ N件の新しい投稿」で知らせる。各投稿にいいね数・コメント数を表示
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
| [API 仕様書（OpenAPI）](docs/openapi.yaml) | コードから自動で作る API 仕様書。ブラウザでは Swagger UI で見る（[API設計](docs/api.md)） |
| [技術スタック](docs/tech-stack.md) | 使う技術とバージョン |
| [インフラ構成](docs/infrastructure.md) | AWS の構成図（暫定案） |
| [機能定義書](docs/feature-specs/README.md) | 機能分類ごとの処理の流れ・エラー時の動き・受け入れ条件（8本） |
| [テスト計画書](docs/test-plan.md) | テストの技法（ブラックボックス・ホワイトボックス）、層ごとの対象とケースの設計、完了の基準 |
| [モックアップ実装計画書](docs/mockup-plan.md) | 本実装前に作成した静的モックアップの内容と動作確認結果 |

ER図・画面遷移図・シーケンス図は Mermaid で書いている。VSCode では拡張機能「Markdown Preview Mermaid Support」を入れるとプレビューで表示できる。

## ディレクトリ構成

```
.
├── backend/    # Spring Boot バックエンド（REST API）
├── frontend/   # React + Vite フロントエンド（SPA）
├── docs/       # 要件定義・設計ドキュメント
├── mockup/     # 実装前に作成した静的HTML/CSS/JSモックアップ
├── localstack/ # LocalStack の起動時にバケットを作るスクリプト
├── scripts/    # 動作確認用のテストデータを入れるスクリプト（seed.sh）
└── docker-compose.yml   # ローカルの PostgreSQL・LocalStack 起動用
```

AWS 構築用の Terraform（`infra/`）は、AWS 上に構築すると決めたときに追加する（[インフラ構成](docs/infrastructure.md)。暫定案）。

## モックアップ

本実装の前に、HTML/CSS/JavaScript だけで動く静的モックを作成した（データはメモリのみ。リロードで初期データに戻る）。

- `mockup/index.html` をブラウザで直接開く（または `cd mockup && python3 -m http.server 8000` で `http://localhost:8000`）
- ログイン画面のデモ用アカウント（例：`yamada@example.com` / `password123`）をクリックしてログインする

内容と動作確認結果は[モックアップ実装計画書](docs/mockup-plan.md)を参照。

## セットアップ・起動方法

[機能一覧](docs/features.md) の機能（F-01〜F-71）はすべて実装済み。各機能の受け入れ条件は、自動テストとブラウザでの操作で確かめてある（[機能定義書](docs/feature-specs/README.md)）。AWS 上への構築は未定（[インフラ構成](docs/infrastructure.md)）。

固定ポート（`backend: 8080` / `frontend: 5173` / `postgres: 5432` / `localstack: 4566`）で起動する。ポートが競合したときの対処など詳しい手順は[.claude/skills/run/SKILL.md](.claude/skills/run/SKILL.md)を参照。

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

### 2. PostgreSQL と LocalStack（S3 の代わり）を起動する

```bash
docker compose up -d postgres localstack
```

LocalStack は画像の保存先（S3）の代わり。起動時にバケット `sns-app-images` が作られる。2026.03.0 以降は起動に LocalStack のアカウントが必要になったため、アカウントなしで動く 4.14.0 に固定している（[技術スタック](docs/tech-stack.md)）。

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

API 仕様書（Swagger UI）は http://localhost:8080/swagger-ui.html で見られる。右上の「Authorize」に、上の新規登録やログインで返る `accessToken` を入れると、ログインが必要な API もその画面から試せる（[API設計](docs/api.md)「API 仕様書（OpenAPI）」）。

### 4. フロントエンドを起動する（別のターミナル）

```bash
cd frontend
npm ci        # 初回だけ
npm run dev
```

http://localhost:5173 を開くとログイン画面が表示される。「新規登録はこちら」からアカウントを作ると、そのままログインしてタイムラインに移る。「✏ 投稿する」から投稿できる。`/api` へのリクエストは Vite のプロキシでバックエンド（8080）に中継される。

動作確認用のテストデータ（テスト用ユーザー・大量の投稿・フォロー関係・いいね・コメント・フォロワー）は `scripts/seed.sh` で入れられる（例：`scripts/seed.sh posts 65`）。使い方は `scripts/seed.sh` を引数なしで実行すると表示される。

### テスト・整形チェック

```bash
cd backend
JAVA_HOME=$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home ./gradlew check   # Spotless の整形チェック + テスト
JAVA_HOME=$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home ./gradlew spotlessApply   # 整形を自動で直す
# テストのあとに、カバレッジのレポート（build/reports/jacoco/test/html/index.html）ができる
# 層ごとの表：（リポジトリ直下で）python3 scripts/coverage-summary.py backend/build/reports/jacoco/test/jacocoTestReport.csv
```

テストは、Testcontainers が**使い捨ての PostgreSQL と LocalStack のコンテナ**を立ち上げて使う（Docker が動いていればよく、`docker compose up` は要らない）。開発用の DB や LocalStack のバケットには書き込まない。コンテナはテストの実行全体で1回だけ立ち上げ、終わると自動で消える。

- `@SpringBootTest` のテストには、必ず `@Import(TestcontainersConfiguration.class)` を付ける。付け忘れると、開発用の DB に書き込まずに接続エラーで失敗する（テスト用の設定の接続先を、わざと存在しない宛先にしてある）
- H2 は使わない。SQL が PostgreSQL 特有の書き方（`ON CONFLICT`・`ILIKE` など）を使っているため、本番と同じ PostgreSQL で確かめる

フロントエンドの型チェック・ビルドと lint：

```bash
cd frontend
npm run build   # TypeScript の型チェック + ビルド
npm run lint    # oxlint
npm test        # テスト（Vitest）。テストは対象のファイルの隣に 〇〇.test.ts で置く
npm run test:coverage   # テストとカバレッジ。frontend/coverage/index.html で、通っていない分岐が分かる
```

API を変えたときは、API 仕様書からフロントの型（`src/api/schema.ts`）を作り直す（[API設計](docs/api.md)「フロントの型の自動生成」）：

```bash
cd frontend
npm ci --prefix tools/openapi-typescript   # 初回だけ（型を作るツールは TypeScript 5 が必要なため、別のフォルダーに入れている）
npm run gen:api                            # docs/openapi.yaml から src/api/schema.ts を作る
```

### CI（GitHub Actions）

PR と main への push のたびに、[.github/workflows/ci.yml](.github/workflows/ci.yml) で次を自動で実行する。マージは CI が通ってから行う。

- バックエンド：`./gradlew check`（PostgreSQL と LocalStack は Testcontainers が立ち上げる）と、API 仕様書 `docs/openapi.yaml` が最新か（テストで書き出したものとコミットしたものが同じか）の確認
- フロントエンド：`npm ci`・`npm run lint`・`npm test`・`npm run build` と、API の型 `src/api/schema.ts` が最新か（`docs/openapi.yaml` から作り直したものとコミットしたものが同じか）の確認

### ライブラリの脆弱性・更新（Dependabot）

- 使っているライブラリに脆弱性が見つかると、GitHub の Security タブ（Dependabot alerts）に表示され、直すための PR が自動で作られる
- 普通の更新は、月1回まとめて PR が作られる（[.github/dependabot.yml](.github/dependabot.yml)）。メジャーバージョンが上がるものは動作確認をしてから取り込む
- バックエンドは、間接的に使っているライブラリも調べられるよう、main への push のたびに依存関係を GitHub に送る（[.github/workflows/dependency-submission.yml](.github/workflows/dependency-submission.yml)）
- springdoc（バックエンド）や openapi-typescript（`frontend/tools/openapi-typescript`）を上げる PR では、生成物（`docs/openapi.yaml`・`frontend/src/api/schema.ts`）の出力が変わることがある。Dependabot は生成物を作り直さないので、その場合は CI の「最新か確認」で落ちる。PR のブランチで `./gradlew test`（backend）と `npm run gen:api`（frontend）を実行し、生成物をコミットして push する
