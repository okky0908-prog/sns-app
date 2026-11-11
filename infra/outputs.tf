output "app_url" {
  description = "アプリのURL（アプリ用の CloudFront。HTTPS）"
  value       = "https://${aws_cloudfront_distribution.app.domain_name}/"
}

output "image_base_url" {
  description = "画像の配信元（画像用の CloudFront）。API のタスク定義の環境変数 IMAGE_BASE_URL に入っている"
  value       = "https://${aws_cloudfront_distribution.images.domain_name}"
}

output "s3_bucket" {
  description = "画像を保存する S3 バケットの名前"
  value       = aws_s3_bucket.images.bucket
}

output "project_name" {
  description = "リソース名のプレフィックス。SSM パラメータストアの場所（/<project_name>/...）にも使う"
  value       = var.project_name
}

output "aws_region" {
  description = "リージョン"
  value       = var.aws_region
}

output "alb_dns_name" {
  description = "ALB の DNS 名。CloudFront 以外からは 403 になる（確認用）"
  value       = aws_lb.this.dns_name
}

output "frontend_bucket" {
  description = "画面（frontend/dist）を置く S3 バケットの名前。deploy.sh が送る"
  value       = aws_s3_bucket.frontend.bucket
}

output "ecr_repository_url" {
  description = "API のコンテナのイメージを置く ECR の URL。deploy.sh が送る"
  value       = aws_ecr_repository.backend.repository_url
}

output "ecs_cluster_name" {
  description = "ECS のクラスターの名前"
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_name" {
  description = "API の ECS のサービスの名前。deploy.sh・servers.sh がタスクの数を変える"
  value       = aws_ecs_service.backend.name
}

output "api_health_alarm_name" {
  description = "「API の正常なタスクがない」アラームの名前。servers.sh stop でわざとタスクを止めるときは通知を止め、start・deploy.sh で戻す"
  value       = aws_cloudwatch_metric_alarm.api_no_healthy_task.alarm_name
}

output "alerts_topic_arn" {
  description = "監視のアラームの通知先（SNS のトピック）"
  value       = aws_sns_topic.alerts.arn
}

output "backend_log_group" {
  description = "API のログ（CloudWatch Logs）。見るときは aws logs tail <この値> --follow"
  value       = aws_cloudwatch_log_group.backend.name
}

output "jwt_secret_parameter_name" {
  description = "JWT の署名鍵を置く SSM パラメータの名前。deploy.sh が初回だけ作る（Terraform は作らない）"
  value       = "${local.ssm_prefix}/jwt-secret"
}

output "rds_endpoint" {
  description = "RDSのエンドポイント（ホスト名:ポート）。API のタスクからだけ接続できる"
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

output "perf_db_task_definition" {
  description = "性能テストで DB を操作する一度きりのタスクの定義（perf/lib/aws-psql.sh が使う）"
  value       = aws_ecs_task_definition.perf_db.family
}

output "perf_db_network" {
  description = "一度きりのタスクを置く場所（API と同じサブネットとセキュリティグループ。RDS は API のセキュリティグループからだけ受け付けるため）"
  value       = "awsvpcConfiguration={subnets=[${data.aws_subnet.primary.id}],securityGroups=[${aws_security_group.backend.id}],assignPublicIp=ENABLED}"
}
