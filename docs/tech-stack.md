# 技術スタック

[要件定義書](./requirements.md) の詳細ドキュメント。X風SNSアプリの技術スタックとバージョンをまとめる。

## バージョンの決め方

- **前回のタスク管理アプリ（`github-demo`）と同じ技術は、同じバージョンを使う**。前回動作確認済みの組み合わせなので、環境構築でつまずきにくく、前回のコードや設定をそのまま参考にできる
  - バージョンの出どころ: `github-demo` の `frontend/package.json`（`package-lock.json`）・`frontend/.nvmrc`・`backend/build.gradle.kts`・`backend/gradle/wrapper/gradle-wrapper.properties`・`docker-compose.yml`・`infra/`（2026-09-30 確認）
- 今回新しく使う技術（認証・ルーティング・S3 など）は、次のルールで決める
  - **Spring Boot が管理しているライブラリ**（Spring Security など）: バージョンを自分で書かず、Spring Boot 4.1.1 が決めるバージョンに任せる。ただし、その版に脆弱性があって Spring Boot の新しい版がまだ出ていないときだけ、`build.gradle.kts` の `extra["〇〇.version"]` で直った版を指定する（今は Tomcat と Jackson。下の表を参照）
  - **それ以外**: 導入するときの最新の安定版を使い、`package.json` / `build.gradle.kts` で固定したうえで、この表の「未定」を書き換える
- 表の「前回と同じ」列: ○ は `github-demo` と同じバージョン、新規 は今回初めて使うもの、更新 は前回と同じ技術だが版を上げたもの
- **例外：脆弱性の修正と Dependabot の更新**。使っているライブラリに脆弱性が見つかったら、前回と同じ版にこだわらず、直った版に上げる。Dependabot の更新 PR も、テストとブラウザでの確認で不具合がなければ取り込む（2026-10-12、Issue #63 で Spring Boot 4.1.1 などに更新）

## フロントエンド

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| フレームワーク | React 19.3.0 | 更新 | 前回は 19.2.8 |
| 言語 | TypeScript 7.0.2 | ○ | |
| ビルド・開発サーバー | Vite 8.3.4（`@vitejs/plugin-react` 6.1.2） | 更新 | 前回は Vite 8.2.1・plugin-react 6.0.5。 開発時は Vite のプロキシで `/api` を Spring Boot に中継する |
| 実行環境 | Node.js 24系（`.nvmrc` で固定） | ○ | |
| Lint | oxlint 1.87.0 | 更新 | 前回は 1.79.0 |
| 型定義 | `@types/react` 19.3.0、`@types/react-dom` 19.3.0、`@types/node` 24.13.3 | 更新 | `@types/react`・`@types/react-dom` は React 19.3 に合わせて更新。`@types/node` は Node.js 24 系に合わせ、メジャーは上げない（Dependabot で除外） |
| 状態管理 | React 標準（useState / useContext 等） | ○ | ログインユーザーの情報は Context で共有する。外部の状態管理ライブラリは使わない |
| サーバー通信 | 標準の fetch API | ○ | JWT を付ける・401 でログイン画面へ移動する、といった共通処理は自前の小さな関数にまとめる |
| スタイリング | CSS Modules | ○ | 追加ライブラリに依存しない |
| ルーティング | React Router 8.4.0 | 新規 | 前回は1画面だったため不要だった。今回は画面ごとに URL を分ける（`/users/:username` など） |
| ドラッグ&ドロップ | 使わない | — | 前回の dnd-kit は今回は不要 |

## バックエンド

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| 言語 | Java 25（25.0.4） | ○ | ローカルは Homebrew の `openjdk@25`、AWS 上は Amazon Corretto 25 |
| フレームワーク | Spring Boot 4.1.1（Spring Framework 7.0.9） | 更新 | 前回は 4.1.0。4.1.0 が使う Spring MVC・Tomcat・Jackson などに脆弱性があったため更新 |
| ビルドツール | Gradle 9.8.1（Kotlin DSL） | 更新 | 前回は 9.5.1 |
| 依存関係の管理 | `io.spring.dependency-management` 1.1.7 | ○ | |
| Web / API | spring-boot-starter-webmvc（組み込み Tomcat 11.0.26） | 更新 | Spring Boot 4.1.1 が使う 11.0.24 には脆弱性があるため、`build.gradle.kts` で 11.0.26 を指定している。**Spring Boot を次に上げるとき、その版が 11.0.26 以上ならこの指定を消す** |
| バリデーション | spring-boot-starter-validation | ○ | 文字数・形式のチェック |
| ヘルスチェック | spring-boot-starter-actuator | ○ | ALB のヘルスチェック用。Actuator の health を `/api/health` で公開する（`management.endpoints.web.base-path` などで設定） |
| テスト | JUnit 5 + Spring Boot Test（MockMvc） | ○ | |
| API 仕様書 | springdoc-openapi 3.1.1（`springdoc-openapi-starter-webmvc-ui`。Swagger UI を含む） | 新規 | コントローラーから OpenAPI 3.1 の仕様書を作る。Spring Boot 4 に対応するのは v3 系（Spring Boot 3 は v2 系）。3.1.1 は Spring Boot 4.1.0 を前提に作られている。Spring Boot を上げるときは、springdoc の対応する版も確認する |
| Javadoc の読み取り | therapi-runtime-javadoc 0.15.0 | 新規 | コンパイル時に Javadoc を残し、springdoc が仕様書の説明文に使う（`@Operation` などの注釈で説明を書き直さなくて済む） |
| コード整形 | Spotless 8.10.3（Google Java Format） | 更新 | `./gradlew check` で整形崩れを検出 |
| 認証 | spring-boot-starter-security（Spring Boot 4.1.1 が管理するバージョン） | 新規 | 前回はログイン機能がなかった |
| JWT | JJWT 0.13.0（`jjwt-api`・`jjwt-impl`・`jjwt-gson`） | 新規 | トークンの発行と検証。JSON の処理には Gson を使う（`jjwt-jackson` は Jackson 2 に依存し、Spring Boot 4 が使う Jackson 3 と混ざるため。Gson のバージョンは Spring Boot が管理） |
| JSON | Jackson 3.1.7（Spring Boot が使うもの） | 更新 | Spring Boot 4.1.1 が使う 3.1.5 には脆弱性があるため、`build.gradle.kts` で 3.1.7 を指定している。**Spring Boot を次に上げるとき、その版が 3.1.7 以上ならこの指定を消す** |
| JSON（Jackson 2） | Jackson 2.21.7（springdoc が使うもの） | 新規 | Spring Boot 本体は Jackson 3 だが、springdoc（API 仕様書の生成。内部の swagger-core）は Jackson 2 を使う。Spring Boot 4.1.1 が決める 2.21.5 には脆弱性があるため、`build.gradle.kts` の `jackson-2-bom.version` で 2.21.7 を指定している（Issue #70）。**Spring Boot を次に上げるとき、その版の Jackson 2 が 2.21.7 以上ならこの指定を消す** |
| パスワードのハッシュ化 | BCrypt（Spring Security に含まれる `BCryptPasswordEncoder`） | 新規 | 追加のライブラリは不要 |
| S3 | AWS SDK for Java v2 2.55.14（`software.amazon.awssdk:bom` で S3 モジュールを使う） | 新規 | 画像のアップロード・削除。同期のクライアントだけを使うので、非同期用の Netty は除外する |

## データベース・永続化

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| DB | PostgreSQL 17（Docker イメージ `postgres:17`、AWS は RDS for PostgreSQL 17） | ○ | |
| DB アクセス | MyBatis（`mybatis-spring-boot-starter` 4.1.0、MyBatis 3.5.19） | 新規 | 前回の Spring Data JPA から変更。SQL を `backend/src/main/resources/mapper/*.xml` に自分で書く。一覧の集計（いいね数・コメント数）やユーザー検索など、SQL を細かく書く場面が多いため。値は必ず `#{}` で渡す |
| JDBC ドライバ | PostgreSQL JDBC 42.7.13（Spring Boot 4.1.1 が管理するバージョン） | 更新 | 前回は 42.7.11（脆弱性あり） |
| マイグレーション | Flyway 12.4.0（`flyway-database-postgresql`） | ○ | テーブル作成・インデックスを SQL ファイルで管理する（V1〜V8）。ユーザー検索用の pg_trgm 拡張は、ユーザーが増えたときに追加する |

## ローカル開発環境

| 項目 | 技術・バージョン | 前回と同じ | 理由・補足 |
|---|---|---|---|
| コンテナ | Docker Compose | ○ | |
| DB | `postgres:17` | ○ | |
| S3 の代わり | LocalStack 4.14.0（Docker イメージ `localstack/localstack:4.14.0`、S3 だけ起動） | 新規 | AWS に接続せずに画像のアップロードを試せる。**2026.03.0 以降は起動に LocalStack のアカウントと認証トークンが必要になった**ため、トークンなしで動く最後の版 4.14.0 に固定する（更新は受けられないが、使うのは S3 の保存・削除・表示だけで、手元の開発用なので影響は小さい）。無料版はデータを保存しないので、コンテナを作り直すと画像は消える |

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
| バックエンド | MyBatis、Spring Security、JJWT、AWS SDK for Java v2 | Spring Data JPA（Hibernate） |
| 開発環境 | LocalStack | |
| インフラ | ALB、S3、CloudFront | |
