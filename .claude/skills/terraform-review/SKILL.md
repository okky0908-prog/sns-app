---
name: terraform-review
description: このリポジトリのTerraformコード（infra/配下）に対する品質チェックを行う。セキュリティ（秘密情報の直書き・過剰な公開範囲・S3の公開・IAMの権限）、コスト（無料利用枠・ALB等の課金リソース）、保守性（fmt・命名・変数定義）、可用性（冪等性・依存関係・ALBのヘルスチェック）の観点でチェックし、結果を一覧で報告する。「Terraformの品質チェック」「infraのレビュー」等を求められたら使う。
---

# Terraformコード品質チェック

`infra/`配下のTerraformコード（`.tf`ファイル、`cloudfront_functions/`、`deploy.sh`、`servers.sh`等の関連スクリプト、`backend/Dockerfile`を含む）を、以下の観点でチェックする。チェック結果は「問題なし／要修正」を明示し、要修正の項目は該当ファイル・行を示して報告する。

## 前提：このアプリの構成

チェックの基準は [インフラ構成](../../../docs/infrastructure.md) とする。要点は次のとおり。

- EC2 は使わない。CloudFront（アプリ用。HTTPS の入口）がパスで振り分ける
  - `/api/*` → ALB → ECS Fargate（Spring Boot 8080番。コンテナのイメージは ECR）→ RDS for PostgreSQL 17
  - それ以外 → 画面の S3（非公開。OAC）。画面の URL は CloudFront Functions（`cloudfront_functions/spa_rewrite.js`）で `/index.html` に書き換える
- 画像は S3（非公開）に保存し、画像用の CloudFront（OAC）経由で配信する。API はタスクロールで S3 にアクセスする
- DB のパスワード・JWT の署名鍵は SSM パラメータストア（SecureString）に置き、タスク定義の `secrets` で渡す。JWT の署名鍵は `deploy.sh` が初回だけ作る（Terraform の state に入れない）
- 既定の VPC を使い、Fargate のタスクはパブリックサブネットに置いて公開IPを付ける（NAT Gateway は使わない）
- 学習用のため、使わない期間はタスクを 0 にする（`servers.sh stop`）か、`terraform destroy` で削除する前提

## 1. セキュリティ

- [ ] パスワード・APIキー・JWTの署名鍵等の秘密情報が`.tf`ファイルにハードコードされていないか（`variable`経由になっているか）
- [ ] 秘密情報を扱う`variable`に`sensitive = true`が付与されているか
- [ ] `terraform.tfvars`・`*.tfstate`・`*.tfstate.backup`・`.terraform/`が`.gitignore`されているか（stateには秘密情報が平文で残るため）
- [ ] セキュリティグループのingressが必要最小限か
  - [ ] `0.0.0.0/0`から受け付けるものがないか。ALB の 80番は CloudFront のプレフィックスリスト（`com.amazonaws.global.cloudfront.origin-facing`）からだけか
  - [ ] ALB が内部用（`internal = true`）で、プライベートサブネット（インターネットゲートウェイへの経路がないルートテーブル）に置かれているか。CloudFront からは VPC オリジン（`aws_cloudfront_vpc_origin`）で届くか（インターネットに出さないため）
  - [ ] Fargate のタスク（8080番）が、**ALBのセキュリティグループからだけ**受け付けているか（公開IPに直接アクセスできないか）
  - [ ] SSH（22番）など、ほかの受け付けがないか（コンテナに入るのは ECS Exec を使う）
  - [ ] RDS（5432番）が API のタスクのセキュリティグループからだけ受け付けているか
- [ ] RDSが`publicly_accessible = false`になっているか
- [ ] S3バケット（画像・画面の2つ）が非公開になっているか
  - [ ] `aws_s3_bucket_public_access_block`で4項目（`block_public_acls`・`block_public_policy`・`ignore_public_acls`・`restrict_public_buckets`）がすべて`true`か
  - [ ] バケットポリシーで`s3:GetObject`・`s3:ListBucket`を許可しているのが CloudFront（OAC、`AWS:SourceArn`で自分のディストリビューションに限定）だけか（画像 → 画像用、画面 → アプリ用）
  - [ ] `s3:ListBucket` を許可しているバケットで、CloudFront の `/` が S3 のルート（一覧）に届かないか（`default_root_object` があるか）
- [ ] IAMポリシーが最小権限になっているか
  - [ ] タスクロールの S3 の権限が、画像のバケットに対する`s3:PutObject`・`s3:DeleteObject`だけか（`s3:*`や`Resource: "*"`になっていないか）。ほかは ECS Exec 用の`ssmmessages:*Channel`だけか
  - [ ] タスクの実行ロールが読める SSM パラメータが、このアプリの2つ（DB のパスワード・JWT の署名鍵）だけか
  - [ ] `terraform-iam-policy.json`（Terraform・deploy.sh を実行する IAM ユーザー）が、作るリソースの名前（`sns-app-*`）に絞れるものは絞っているか。`iam:PassRole`が`ecs-tasks.amazonaws.com`に限られているか
  - [ ] `Action: "*"`や無条件の`AdministratorAccess`相当を使っていないか
- [ ] コンテナにアクセスキー（`AWS_ACCESS_KEY_ID`等）を渡していないか（タスクロールで足りるため）
- [ ] タスク定義の`environment`に秘密の値（パスワード・署名鍵）を直接書いていないか（`secrets`で SSM から渡しているか）
- [ ] コンテナを root で動かしていないか（`backend/Dockerfile`の`USER`）
- [ ] CloudFront（アプリ用）が HTTP を HTTPS にリダイレクトし（`redirect-to-https`）、API と`index.html`をキャッシュせず（`Managed-CachingDisabled`）、`/api/*`ではヘッダー・Cookie をすべて ALB へ送っているか（`Managed-AllViewer`。GET の `Authorization` ヘッダーを落とさないため）

## 2. コスト管理

- [ ] DBインスタンスクラスが、無料利用枠の方針（RDS `db.t3.micro`・20GB・シングルAZ）に沿っているか
- [ ] Fargate のタスクの大きさ（`backend_cpu`・`backend_memory`）が必要以上でないか。タスクの数を Terraform が勝手に戻さないか（`desired_count`を`ignore_changes`にしているか）
- [ ] ECR の古いイメージを消す設定（ライフサイクルポリシー）・CloudWatch Logs の保存日数があるか（保存の料金が増え続けないか）
- [ ] Elastic IP等、未使用時にも課金され得るリソースを不要に作成していないか
- [ ] NAT Gatewayを作っていないか（Fargate はパブリックサブネットに置く設計のため不要）
- [ ] ALBは設計上必要なリソースなので「不要な課金リソース」とはみなさない。ただし、止められず時間課金されるため、`terraform destroy`で他のリソースと一緒に削除できる状態になっているかを確認する
- [ ] RDS の自動バックアップ（`backup_retention_period`）・マルチAZ が無効になっているか（学習用途のため）
- [ ] CloudFront の価格クラスが必要以上に広くないか（例：`PriceClass_200` 以下）
- [ ] `terraform destroy`が問題なく通る設定になっているか
  - [ ] RDS：`deletion_protection = false`・`skip_final_snapshot = true`（学習用途で頻繁に作り直す前提）
  - [ ] ALB：`enable_deletion_protection = false`
  - [ ] S3：中に画像・画面のファイルが残っていても削除できるか（`force_destroy = true`）
  - [ ] ECR：イメージが残っていても削除できるか（`force_delete = true`）

## 3. 保守性・可読性

- [ ] `terraform fmt -check`が通るか（フォーマット崩れがないか）
- [ ] `terraform validate`が通るか
- [ ] すべての`variable`に`description`・`type`が設定されているか
- [ ] リソース名・タグの命名が一貫しているか（プレフィックス変数等で統一されているか、ハードコードされた名前が散在していないか）
- [ ] `required_providers`・`required_version`でバージョンが適切に固定・範囲指定されているか
- [ ] `deploy.sh`・`servers.sh`が使う値（ECR の URL・ECS のクラスターとサービスの名前・画面のバケット・JWT の署名鍵のパラメータ名・ログのグループ）が`output`で取り出せるか
- [ ] アプリの設定（`S3_BUCKET`・`IMAGE_BASE_URL`等）が、タスク定義の`environment`に Terraform の値から入っているか

## 4. 可用性・信頼性

- [ ] ALB と RDS のサブネットが、2つ以上のアベイラビリティゾーンにまたがって指定されているか（どちらも1つのAZだけでは作成できないため）
- [ ] ALB のターゲットグループのヘルスチェックが正しいか
  - [ ] API：`GET /api/health` が 200（ポート 8080、ターゲットの種類は`ip`）
  - [ ] Spring Boot の起動（Flyway を含む）を待つ猶予（`health_check_grace_period_seconds`）があるか
- [ ] ALB のリスナールールで、`/api/*`だけを API のターゲットグループへ送り、それ以外は 403 か
- [ ] 入れ替えのときに止まる時間がないか（`deployment_minimum_healthy_percent = 100`）。失敗したら前の版に戻るか（`deployment_circuit_breaker`）
- [ ] 画面の URL（`/users/yamada`など）を開いたり読み込み直したりしても、`index.html`が返るか（CloudFront Functions。`node --test infra/cloudfront_functions/*.test.mjs`が通るか）。CloudFront の「エラーのときに index.html を返す」設定で代わりにしていないか（API の 404・403 まで画面に置き換わるため）
- [ ] `deploy.sh`が冪等か（2回目以降も安全か。例：JWT の署名鍵は、すでにあれば作り直さない）
- [ ] リソース間の依存関係が正しく表現されているか（暗黙の参照 or 明示的な`depends_on`）
- [ ] 出力（`output`）に必要な情報が過不足なく定義されているか、秘密情報を含む出力に`sensitive = true`が付与されているか

## 実行手順

1. `terraform -chdir=infra fmt -check -diff` でフォーマット崩れを確認
2. `terraform -chdir=infra validate` で構文エラーを確認
3. `node --test infra/cloudfront_functions/*.test.mjs` で CloudFront Functions を確認
4. 上記チェックリストに沿って`infra/*.tf`・`infra/cloudfront_functions/`・`infra/deploy.sh`・`infra/servers.sh`・`backend/Dockerfile`を目視レビュー
5. [インフラ構成](../../../docs/infrastructure.md) と食い違っている箇所があれば、コードとドキュメントのどちらを直すべきかを添えて報告する
5. 見つかった問題は、重大度（セキュリティ > コスト > 保守性・可用性）の順に報告する
