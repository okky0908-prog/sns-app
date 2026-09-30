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
| データベース | PostgreSQL 17（Spring Data JPA + Flyway） |
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

実装開始後に記載する。
