# 技術スタック

[要件定義書](./requirements.md) の詳細ドキュメント。X風SNSアプリの技術スタックとバージョンをまとめる。

## バージョンの決め方

- **前回のタスク管理アプリ（`github-demo`）と同じ技術は、同じバージョンを使う**。前回動作確認済みの組み合わせなので、環境構築でつまずきにくく、前回のコードや設定をそのまま参考にできる
  - バージョンの出どころ: `github-demo` の `frontend/package.json`（`package-lock.json`）・`frontend/.nvmrc`・`backend/build.gradle.kts`・`backend/gradle/wrapper/gradle-wrapper.properties`・`docker-compose.yml`・`infra/`（2026-09-30 確認）
- 今回新しく使う技術（認証・ルーティング・S3 など）は、次のルールで決める
  - **Spring Boot が管理しているライブラリ**（Spring Security など）: バージョンを自分で書かず、Spring Boot 4.1.0 が決めるバージョンに任せる
  - **それ以外**: 導入するときの最新の安定版を使い、`package.json` / `build.gradle.kts` で固定したうえで、この表の「未定」を書き換える
- 表の「前回と同じ」列: ○ は `github-demo` と同じバージョン、新規 は今回初めて使うもの

## フロントエンド

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| フレームワーク | React 19.2.8 | ○ | |
| 言語 | TypeScript 7.0.2 | ○ | |
| ビルド・開発サーバー | Vite 8.2.1（`@vitejs/plugin-react` 6.0.5） | ○ | 開発時は Vite のプロキシで `/api` を Spring Boot に中継する |
| 実行環境 | Node.js 24系（`.nvmrc` で固定） | ○ | |
| Lint | oxlint 1.79.0 | ○ | |
| 型定義 | `@types/react` 19.2.17、`@types/react-dom` 19.2.3、`@types/node` 24.13.3 | ○ | |
| 状態管理 | React 標準（useState / useContext 等） | ○ | ログインユーザーの情報は Context で共有する。外部の状態管理ライブラリは使わない |
| サーバー通信 | 標準の fetch API | ○ | JWT を付ける・401 でログイン画面へ移動する、といった共通処理は自前の小さな関数にまとめる |
| スタイリング | CSS Modules | ○ | 追加ライブラリに依存しない |
| ルーティング | React Router（バージョン未定） | 新規 | 前回は1画面だったため不要だった。今回は画面ごとに URL を分ける（`/users/:username` など） |
| ドラッグ&ドロップ | 使わない | — | 前回の dnd-kit は今回は不要 |

## バックエンド

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| 言語 | Java 25（25.0.4） | ○ | ローカルは Homebrew の `openjdk@25`、AWS 上は Amazon Corretto 25 |
| フレームワーク | Spring Boot 4.1.0（Spring Framework 7.0.8） | ○ | |
| ビルドツール | Gradle 9.5.1（Kotlin DSL） | ○ | |
| 依存関係の管理 | `io.spring.dependency-management` 1.1.7 | ○ | |
| Web / API | spring-boot-starter-webmvc（組み込み Tomcat 11.0.22） | ○ | |
| バリデーション | spring-boot-starter-validation | ○ | 文字数・形式のチェック |
| ヘルスチェック | spring-boot-starter-actuator | ○ | ALB のヘルスチェック用。Actuator の health を `/api/health` で公開する（`management.endpoints.web.base-path` などで設定） |
| テスト | JUnit 5 + Spring Boot Test（MockMvc） | ○ | |
| コード整形 | Spotless 8.10.0（Google Java Format） | ○ | `./gradlew check` で整形崩れを検出 |
| 認証 | spring-boot-starter-security（Spring Boot 4.1.0 が管理するバージョン） | 新規 | 前回はログイン機能がなかった |
| JWT | JJWT（`io.jsonwebtoken:jjwt-api` ほか。バージョン未定） | 新規 | トークンの発行と検証 |
| パスワードのハッシュ化 | BCrypt（Spring Security に含まれる `BCryptPasswordEncoder`） | 新規 | 追加のライブラリは不要 |
| S3 | AWS SDK for Java v2（`software.amazon.awssdk:bom` で S3 モジュールを使う。バージョン未定） | 新規 | 画像のアップロード・削除 |

## データベース・永続化

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| DB | PostgreSQL 17（Docker イメージ `postgres:17`、AWS は RDS for PostgreSQL 17） | ○ | |
| ORM | Spring Data JPA（Hibernate ORM 7.4.1.Final） | ○ | 一覧の集計（いいね数・コメント数）はネイティブ SQL も使う |
| JDBC ドライバ | PostgreSQL JDBC 42.7.11 | ○ | |
| マイグレーション | Flyway 12.4.0（`flyway-database-postgresql`） | ○ | テーブル作成・インデックス・pg_trgm 拡張の有効化を SQL ファイルで管理する |

## ローカル開発環境

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| コンテナ | Docker Compose | ○ | |
| DB | `postgres:17` | ○ | |
| S3 の代わり | LocalStack（Docker イメージのバージョン未定） | 新規 | AWS に接続せずに画像のアップロードを試せる |

## インフラ（AWS）

構成は [インフラ構成](infrastructure.md) を参照（暫定案）。

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| 構築 | Terraform | ○ | |
| サーバーの OS | Amazon Linux 2023 | ○ | |
| Java（サーバー） | Amazon Corretto 25（`java-25-amazon-corretto`） | ○ | |
| Web サーバー | nginx（Amazon Linux 2023 のパッケージ） | ○ | フロントエンドの静的ファイルを返す |
| DB | RDS for PostgreSQL 17 | ○ | |
| ロードバランサー | ALB | 新規 | |
| 画像 | S3 + CloudFront | 新規 | |

## 前回（`github-demo`）との違いのまとめ

| 区分 | 追加するもの | 使わなくなるもの |
|---|---|---|
| フロントエンド | React Router | dnd-kit |
| バックエンド | Spring Security、JJWT、AWS SDK for Java v2 | |
| 開発環境 | LocalStack | |
| インフラ | ALB、S3、CloudFront | |
