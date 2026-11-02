# SNSアプリの本番環境（docs/infrastructure.md）
#
#   ブラウザ ─HTTPS→ CloudFront（アプリ用）─HTTP→ ALB ─┬─ /api/* → バックエンドの EC2（Spring Boot 8080番）→ RDS for PostgreSQL 17
#                                                         └─ それ以外 → フロントエンドの EC2（nginx 80番）
#   ブラウザ ─HTTPS→ CloudFront（画像用）─OAC→ S3（非公開）
#
# ファイルの分け方：
#   main.tf             … Terraform・プロバイダーの設定、既定の VPC などの読み取り
#   security_groups.tf  … セキュリティグループ
#   alb.tf              … ALB・ターゲットグループ・リスナー
#   ec2.tf              … EC2 2台・キーペア・バックエンドの IAM ロール
#   rds.tf              … RDS
#   images.tf           … 画像の S3 バケットと、画像用の CloudFront
#   cloudfront_app.tf   … アプリ用の CloudFront（HTTPS の入口）
#   budget.tf           … 請求アラート

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
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

# EC2 と RDS を置くアベイラビリティゾーン。インスタンスタイプを使えるもののうち、最初のもの（東京なら a）
data "aws_ec2_instance_type_offerings" "available" {
  location_type = "availability-zone"

  filter {
    name   = "instance-type"
    values = [var.instance_type]
  }
}

locals {
  primary_az = sort(data.aws_ec2_instance_type_offerings.available.locations)[0]
}

data "aws_subnet" "primary" {
  vpc_id            = data.aws_vpc.default.id
  availability_zone = local.primary_az
  default_for_az    = true
}

# ALB のサブネットは2つ（EC2 のいるアベイラビリティゾーンと、もう1つ）にする。
# ALB にはサブネットごとに公開IPv4 が付いて課金されるので、必要な数（2つ以上のアベイラビリティゾーン）だけにする。
# 既定のサブネットはアベイラビリティゾーンごとに1つなので、違うサブネットを選べば違うアベイラビリティゾーンになる
locals {
  alb_subnet_ids = concat(
    [data.aws_subnet.primary.id],
    slice(sort([for id in data.aws_subnets.default.ids : id if id != data.aws_subnet.primary.id]), 0, 1),
  )
}

# 最新の Amazon Linux 2023 の AMI
data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}
