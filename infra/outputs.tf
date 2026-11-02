output "app_url" {
  description = "アプリのURL（アプリ用の CloudFront。HTTPS）"
  value       = "https://${aws_cloudfront_distribution.app.domain_name}/"
}

output "image_base_url" {
  description = "画像の配信元（画像用の CloudFront）。バックエンドの環境変数 IMAGE_BASE_URL に入れる"
  value       = "https://${aws_cloudfront_distribution.images.domain_name}"
}

output "s3_bucket" {
  description = "画像を保存する S3 バケットの名前。バックエンドの環境変数 S3_BUCKET に入れる"
  value       = aws_s3_bucket.images.bucket
}

output "project_name" {
  description = "リソース名のプレフィックス。サーバー上のフォルダ（/opt/<project_name>）や systemd のサービス名にも使う"
  value       = var.project_name
}

output "aws_region" {
  description = "リージョン。バックエンドの環境変数 AWS_REGION に入れる"
  value       = var.aws_region
}

output "alb_dns_name" {
  description = "ALB の DNS 名。CloudFront 以外からは 403 になる（確認用）"
  value       = aws_lb.this.dns_name
}

output "frontend_instance_id" {
  description = "フロントエンドのサーバー（EC2）のID"
  value       = aws_instance.frontend.id
}

output "backend_instance_id" {
  description = "バックエンドのサーバー（EC2）のID"
  value       = aws_instance.backend.id
}

output "frontend_public_ip" {
  description = "フロントエンドのサーバーの公開IP（SSH用。EC2を起動するたびに変わる）"
  value       = aws_instance.frontend.public_ip
}

output "backend_public_ip" {
  description = "バックエンドのサーバーの公開IP（SSH用。EC2を起動するたびに変わる）"
  value       = aws_instance.backend.public_ip
}

output "rds_endpoint" {
  description = "RDSのエンドポイント（ホスト名:ポート）。バックエンドのサーバーからだけ接続できる"
  value       = aws_db_instance.this.endpoint
}

output "db_name" {
  description = "RDSのデータベース名"
  value       = aws_db_instance.this.db_name
}

output "db_username" {
  description = "RDSの管理者ユーザー名"
  value       = aws_db_instance.this.username
  sensitive   = true
}
