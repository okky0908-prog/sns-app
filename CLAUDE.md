# リポジトリ運用ルール（必須）

Claude Codeはこのリポジトリで作業する際、以下のワークフローを必ず守ること。
`main`ブランチへの直接コミット・直接pushは一切禁止（GitHub側のブランチ保護でも技術的に禁止されている）。

## 1. 作業を始める前に、GitHub Issueを作成する

`gh issue create` で、これから行う作業を説明するIssueを作成する。

## 2. Issueに紐づくブランチを作成する

`main`から以下の命名規則でブランチを作成する。

`<type>/<issue番号>-<短い英語スラッグ>`

- `type`は `feat` `fix` `chore` `docs` `refactor` `test` のいずれか
- 例：`feat/12-add-login-form`、`fix/13-null-pointer-on-save`

## 3. そのブランチ上で作業・コミットする

コミットメッセージは通常のルールに従う（このリポジトリはCo-Authored-By規約あり）。

## 4. 作業完了後、PRを作成する

`gh pr create` で `main` 宛のPRを作成し、本文に `Closes #<Issue番号>` を含めてIssueと紐づける。

## 5. マージ

レビュー承認は必須設定にしていない（ソロ開発のため）が、CIやビルドが通ることを確認してからマージする。マージ後は作業ブランチを削除する（`delete_branch_on_merge`設定によりGitHub側で自動削除される）。

# 動作確認時のポート運用ルール（必須）

動作確認のためにバックエンド・フロントエンドのサーバーを起動する際は、必ず以下の固定ポートを使用すること。

- バックエンド（Spring Boot）: `8080`（`backend/src/main/resources/application.yml`に`server.port`の指定なし＝デフォルト値）
- フロントエンド（Vite）: `5173`（`frontend/vite.config.ts`に`server.port`の指定なし＝デフォルト値）
- PostgreSQL（docker-compose）: `5432`（`.env`の`POSTGRES_PORT`）
- LocalStack（docker-compose、S3の代わり）: `4566`（LocalStackのデフォルト値）

**ポートが競合してサーバーが起動できない場合、別の空きポートに逃がして起動してはならない。** 必ず該当ポートを占有しているプロセスを特定して停止し、上記の指定ポートで起動し直すこと。

```bash
# 例: 8080番ポートを使っているプロセスを特定して停止する
lsof -ti tcp:8080 | xargs kill
```

停止対象のプロセスが今回のセッションと無関係な重要プロセスである可能性がある場合は、kill前にユーザーに確認する。

起動手順の詳細は`.claude/skills/run/SKILL.md`を参照。
