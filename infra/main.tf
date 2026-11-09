# SNSアプリの本番環境（docs/infrastructure.md）。EC2 は使わない
#
#   ブラウザ ─HTTPS→ CloudFront（アプリ用）─┬─ /api/* ─HTTP→ ALB → ECS Fargate（Spring Boot 8080番）→ RDS for PostgreSQL 17
#                                             └─ それ以外 ─OAC→ S3（画面。React のビルド結果）
#   ブラウザ ─HTTPS→ CloudFront（画像用）─OAC→ S3（画像。非公開）
#
# ファイルの分け方：
#   main.tf             … Terraform・プロバイダーの設定、既定の VPC などの読み取り
#   security_groups.tf  … セキュリティグループ
#   alb.tf              … ALB・ターゲットグループ・リスナー
#   ecs.tf              … ECR・ECS（クラスター・タスク定義・サービス）・IAM ロール・ログ・秘密の値（SSM）
#   rds.tf              … RDS
#   frontend.tf         … 画面の S3 バケットと、画面の URL を index.html に書き換える CloudFront Functions
#   images.tf           … 画像の S3 バケットと、画像用の CloudFront
#   cloudfront_app.tf   … アプリ用の CloudFront（HTTPS の入口。画面と API を振り分ける）
#   budget.tf           … 請求アラート

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project = var.project_name
    }
  }
}

data "aws_caller_identity" "current" {}

# --- ネットワーク ---
# 既定の VPC・サブネットを使う（新しく VPC を作らない。NAT ゲートウェイも使わない）

data "aws_vpc" "default" {
  default = true
}

# 既定の VPC の、各アベイラビリティゾーンの既定のサブネット。
# ALB と RDS は、2つ以上のアベイラビリティゾーンのサブネットを指定する必要がある
data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }

  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# API（Fargate）と RDS を置くアベイラビリティゾーン。使えるもののうち、最初のもの（東京なら a）。
# 同じところに置き、アベイラビリティゾーンをまたぐ通信の料金と遅れを避ける
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  primary_az = sort(data.aws_availability_zones.available.names)[0]
}

data "aws_subnet" "primary" {
  vpc_id            = data.aws_vpc.default.id
  availability_zone = local.primary_az
  default_for_az    = true
}

# ALB は2つのアベイラビリティゾーン（Fargate のいるところと、もう1つ）に置く（ALB は2つ以上が必要）。
# 既定のサブネットはアベイラビリティゾーンごとに1つなので、違うサブネットを選べば違うアベイラビリティゾーンになる。
# ALB 自体は、この2つのアベイラビリティゾーンに作るプライベートサブネットに置く（network.tf）
data "aws_subnet" "secondary" {
  id = sort([for id in data.aws_subnets.default.ids : id if id != data.aws_subnet.primary.id])[0]
}

locals {
  alb_azs = [local.primary_az, data.aws_subnet.secondary.availability_zone]
}
