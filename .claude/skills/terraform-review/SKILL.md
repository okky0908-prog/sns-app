---
name: terraform-review
description: このリポジトリのTerraformコード（infra/配下）に対する品質チェックを行う。セキュリティ（秘密情報の直書き・過剰な公開範囲・S3の公開・IAMの権限）、コスト（無料利用枠・ALB等の課金リソース）、保守性（fmt・命名・変数定義）、可用性（冪等性・依存関係・ALBのヘルスチェック）の観点でチェックし、結果を一覧で報告する。「Terraformの品質チェック」「infraのレビュー」等を求められたら使う。
---

# Terraformコード品質チェック

`infra/`配下のTerraformコード（`.tf`ファイル、`user_data*.sh`、`deploy.sh`、`servers.sh`等の関連スクリプトを含む）を、以下の観点でチェックする。チェック結果は「問題なし／要修正」を明示し、要修正の項目は該当ファイル・行を示して報告する。

## 前提：このアプリの構成

チェックの基準は [インフラ構成](../../../docs/infrastructure.md) とする。要点は次のとおり。

- ALB → EC2 2台（フロントエンド：nginx / バックエンド：Spring Boot 8080番）→ RDS for PostgreSQL 17
- ALB はパスで振り分ける（`/api/*` → バックエンド、それ以外 → フロントエンド）
- 画像は S3（非公開）に保存し、CloudFront（OAC）経由で配信する。バックエンドの EC2 は IAM ロールで S3 にアクセスする
- 既定の VPC を使い、EC2 はパブリックサブネットに置く（NAT Gateway は使わない）
- 学習用のため、使わない期間は `terraform destroy` で削除する前提

## 1. セキュリティ

- [ ] パスワード・APIキー・JWTの署名鍵等の秘密情報が`.tf`ファイルにハードコードされていないか（`variable`経由になっているか）
- [ ] 秘密情報を扱う`variable`に`sensitive = true`が付与されているか
- [ ] `terraform.tfvars`・`*.tfstate`・`*.tfstate.backup`・`.terraform/`が`.gitignore`されているか（stateには秘密情報が平文で残るため）
- [ ] セキュリティグループのingressが必要最小限か
  - [ ] `0.0.0.0/0`からの受け付けは ALB の 80番（HTTPS化後は443番）だけになっているか
  - [ ] フロントエンドの EC2（80番）・バックエンドの EC2（8080番）が、**ALBのセキュリティグループからだけ**受け付けているか（EC2 に直接アクセスできないか）
  - [ ] SSH（22番）が送信元IP（自分のPCのIP）で絞られているか
  - [ ] RDS（5432番）がバックエンドの EC2 のセキュリティグループからだけ受け付けているか
- [ ] RDSが`publicly_accessible = false`になっているか
- [ ] S3バケットが非公開になっているか
  - [ ] `aws_s3_bucket_public_access_block`で4項目（`block_public_acls`・`block_public_policy`・`ignore_public_acls`・`restrict_public_buckets`）がすべて`true`か
  - [ ] バケットポリシーで`s3:GetObject`を許可しているのが CloudFront（OAC、`AWS:SourceArn`で自分のディストリビューションに限定）だけか
- [ ] IAMポリシーが最小権限になっているか
  - [ ] バックエンドの EC2 の IAM ロールに付けた権限が、このバケットに対する`s3:PutObject`・`s3:DeleteObject`（必要なら`s3:GetObject`）だけか（`s3:*`や`Resource: "*"`になっていないか）
  - [ ] `Action: "*"`や無条件の`AdministratorAccess`相当を使っていないか
- [ ] EC2 にアクセスキー（`AWS_ACCESS_KEY_ID`等）を渡していないか（IAMロールで足りるため）
- [ ] HTTPS化後：ALB のリスナーで80番→443番のリダイレクトがあり、ACMの証明書を使っているか

## 2. コスト管理

- [ ] インスタンスタイプ・DBインスタンスクラスが、無料利用枠の方針（EC2 `t3.micro`・RDS `db.t3.micro`・20GB・シングルAZ）に沿っているか
- [ ] Elastic IP等、未使用時にも課金され得るリソースを不要に作成していないか
- [ ] NAT Gatewayを作っていないか（EC2 はパブリックサブネットに置く設計のため不要）
- [ ] ALBは設計上必要なリソースなので「不要な課金リソース」とはみなさない。ただし、止められず時間課金されるため、`terraform destroy`で他のリソースと一緒に削除できる状態になっているかを確認する
- [ ] RDS の自動バックアップ（`backup_retention_period`）・マルチAZ が無効になっているか（学習用途のため）
- [ ] CloudFront の価格クラスが必要以上に広くないか（例：`PriceClass_200` 以下）
- [ ] `terraform destroy`が問題なく通る設定になっているか
  - [ ] RDS：`deletion_protection = false`・`skip_final_snapshot = true`（学習用途で頻繁に作り直す前提）
  - [ ] ALB：`enable_deletion_protection = false`
  - [ ] S3：中に画像が残っていても削除できるか（`force_destroy = true`、または削除前に空にする手順が`deploy.sh`等に書かれているか）

## 3. 保守性・可読性

- [ ] `terraform fmt -check`が通るか（フォーマット崩れがないか）
- [ ] `terraform validate`が通るか
- [ ] すべての`variable`に`description`・`type`が設定されているか
- [ ] リソース名・タグの命名が一貫しているか（プレフィックス変数等で統一されているか、ハードコードされた名前が散在していないか）
- [ ] `required_providers`・`required_version`でバージョンが適切に固定・範囲指定されているか
- [ ] S3バケット名・CloudFront の URL・ALB の DNS 名など、アプリの設定（`backend.env`の`S3_BUCKET`・`IMAGE_BASE_URL`等）に渡す値が`output`で取り出せるか

## 4. 可用性・信頼性

- [ ] ALB と RDS のサブネットが、2つ以上のアベイラビリティゾーンにまたがって指定されているか（どちらも1つのAZだけでは作成できないため）
- [ ] ALB のターゲットグループのヘルスチェックが正しいか
  - [ ] フロントエンド：`GET /` が 200
  - [ ] バックエンド：`GET /api/health` が 200（ポート 8080）
- [ ] ALB のリスナールールで`/api/*`がバックエンド、既定のアクションがフロントエンドのターゲットグループになっているか
- [ ] `user_data*.sh`等の起動時スクリプトが冪等か（再実行・再作成されても安全か。例：スワップファイル作成前の存在チェック）
- [ ] nginx の設定で、存在しないパスにも`index.html`を返しているか（`try_files $uri /index.html;`。React Router の URL を直接開いたりリロードしたりしても404にならないため）
- [ ] リソース間の依存関係が正しく表現されているか（暗黙の参照 or 明示的な`depends_on`）
- [ ] 出力（`output`）に必要な情報が過不足なく定義されているか、秘密情報を含む出力に`sensitive = true`が付与されているか

## 実行手順

1. `terraform -chdir=infra fmt -check -diff` でフォーマット崩れを確認
2. `terraform -chdir=infra validate` で構文エラーを確認
3. 上記チェックリストに沿って`infra/*.tf`・`infra/user_data*.sh`・`infra/deploy.sh`・`infra/servers.sh`を目視レビュー
4. [インフラ構成](../../../docs/infrastructure.md) と食い違っている箇所があれば、コードとドキュメントのどちらを直すべきかを添えて報告する
5. 見つかった問題は、重大度（セキュリティ > コスト > 保守性・可用性）の順に報告する
