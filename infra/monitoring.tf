# --- 監視のアラーム（CloudWatch Alarms。Issue #162）---
# docs/observability.md「5.2 監視項目」を、AWS の標準の道具（段階 2）で作る。
# 緊急度 P1・P2 はメールで知らせ、P3 は通知せずに記録だけにする（5.1）。
# アラームの説明（alarm_description）には、5.3 のとおり「何が起きているか・影響・最初に見るもの」を書く

locals {
  alarm_prefix = "${var.project_name}-"
  # ログから作るメトリクスの名前空間
  log_metric_namespace = "SnsApp"
  # 5.1 の緊急度ごとの通知先
  alarm_actions = {
    P1 = [aws_sns_topic.alerts.arn]
    P2 = [aws_sns_topic.alerts.arn]
    P3 = []
  }
  logs_insights_hint = "CloudWatch Logs の ${aws_cloudwatch_log_group.backend.name} を検索"
}

# --- 通知先 ---

# 暗号化（SSE）は付けない。AWS 管理のキーで暗号化すると、CloudWatch のアラームが通知を送れないため
resource "aws_sns_topic" "alerts" {
  name = "${var.project_name}-alerts"
}

# 初回は確認のメール（AWS Notification - Subscription Confirmation）が届く。リンクを押すまで通知は届かない
resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.budget_alert_email
}

# --- ログから作るメトリクス（構造化ログの JSON。docs/observability.md「3.2」）---

locals {
  # name: アラーム・メトリクスの名前、pattern: メトリクスフィルターの条件、
  # threshold・period・evaluation_periods: 「period 秒ごとの件数が threshold 以上」が evaluation_periods 回続いたら鳴らす
  log_alarms = {
    db-unavailable = {
      id          = "A3"
      severity    = "P1"
      pattern     = "{ $.error_code = \"SERVICE_UNAVAILABLE\" }"
      threshold   = 3
      period      = 300
      description = "A3 DB につながらない（5分間に3件以上の SERVICE_UNAVAILABLE）。影響：ほとんどの操作が 503 で失敗する。最初に見るもの：RDS の状態・接続数、${local.logs_insights_hint}（error_code = SERVICE_UNAVAILABLE）"
    }
    image-upload-failed = {
      id          = "A5"
      severity    = "P2"
      pattern     = "{ $.error_code = \"IMAGE_UPLOAD_FAILED\" }"
      threshold   = 5
      period      = 600
      description = "A5 画像を保存できない（10分間に5件以上の IMAGE_UPLOAD_FAILED）。影響：画像つきの投稿・アイコンの変更が失敗する。最初に見るもの：S3 の状態、タスクロールの権限、${local.logs_insights_hint}（logger = ImageStorage）"
    }
    login-failed-surge = {
      id          = "A6"
      severity    = "P2"
      pattern     = "{ $.event.action = \"login_failed\" }"
      threshold   = 30
      period      = 600
      description = "A6 ログインの失敗の急増（10分間に30件以上の login_failed）。影響：総当たり攻撃の疑い（同じメールアドレスは5回でロックされる）。最初に見るもの：${local.logs_insights_hint}（event.action = login_failed・login_locked の user.email の偏り）"
    }
    client-error-surge = {
      id          = "A12"
      severity    = "P2"
      pattern     = "{ $.event.action = \"client_error\" }"
      threshold   = 20
      period      = 3600
      description = "A12 画面のエラーの急増（1時間に20件以上の client_error）。影響：画面の一部が動かない可能性。最初に見るもの：${local.logs_insights_hint}（event.action = client_error の error.message・url.path・service.version）。誰でも送れるので、同じ内容の大量の報告は偽物を疑う"
    }
    refresh-token-reuse = {
      id          = "A7"
      severity    = "P3"
      pattern     = "{ $.event.action = \"refresh_token_reuse\" }"
      threshold   = 1
      period      = 300
      description = "A7 リフレッシュトークンの使い回しの検知（1件でも）。影響：トークンが盗まれた疑い（そのユーザーのトークンはすべて無効にした）。最初に見るもの：${local.logs_insights_hint}（そのユーザーの user_id のログイン履歴）"
    }
    invalid-token-surge = {
      id          = "A11"
      severity    = "P3"
      pattern     = "{ $.event.action = \"invalid_token\" }"
      threshold   = 20
      period      = 600
      description = "A11 正しくないアクセストークンの急増（10分間に20件以上の invalid_token）。影響：偽造・総当たりの疑い。最初に見るもの：${local.logs_insights_hint}（event.action = invalid_token の reason）"
    }
  }
}

resource "aws_cloudwatch_log_metric_filter" "log_alarms" {
  for_each = local.log_alarms

  name           = "${local.alarm_prefix}${each.key}"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = each.value.pattern

  metric_transformation {
    name      = each.key
    namespace = local.log_metric_namespace
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "log_alarms" {
  for_each = local.log_alarms

  alarm_name          = "${local.alarm_prefix}${each.key}"
  alarm_description   = "[${each.value.severity}] ${each.value.description}"
  namespace           = local.log_metric_namespace
  metric_name         = each.key
  statistic           = "Sum"
  period              = each.value.period
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = each.value.threshold
  # ログが1件もない時間は 0 件として扱う
  treat_missing_data = "notBreaching"
  alarm_actions      = local.alarm_actions[each.value.severity]
  ok_actions         = local.alarm_actions[each.value.severity]

  depends_on = [aws_cloudwatch_log_metric_filter.log_alarms]
}

# --- AWS のメトリクス ---

locals {
  alb_dimensions = {
    LoadBalancer = aws_lb.this.arn_suffix
    TargetGroup  = aws_lb_target_group.backend.arn_suffix
  }
  ecs_dimensions = {
    ClusterName = aws_ecs_cluster.this.name
    ServiceName = aws_ecs_service.backend.name
  }
}

# A1 の代わり：外形監視（Synthetics）は5分ごとでも月に千円以上かかるので、ALB から見た正常なタスクの数で見る。
# わざとタスクを止めるとき（infra/servers.sh stop）は、このアラームの通知を止める
resource "aws_cloudwatch_metric_alarm" "api_no_healthy_task" {
  alarm_name          = "${local.alarm_prefix}api-no-healthy-task"
  alarm_description   = "[P1] A1（の代わり）API の正常なタスクがない（2分続けて0）。影響：画面は出るが、ログイン・投稿など API を使う操作がすべて失敗する（503）。最初に見るもの：ECS のサービスのイベント、${local.logs_insights_hint}（起動の失敗）。戻し方：直前の版でデプロイし直す"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = local.alb_dimensions
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  # データが来ない（ALB にターゲットがない）ときも、動いていないとみなす
  treat_missing_data = "breaching"
  alarm_actions      = local.alarm_actions.P1
  ok_actions         = local.alarm_actions.P1
}

# A2：5xx（アプリが返したもの＋ALB が返したもの）の割合。リクエストが少ないときは1件の失敗で 100% になるので、10件以上のときだけ判定する
resource "aws_cloudwatch_metric_alarm" "api_5xx_rate" {
  alarm_name          = "${local.alarm_prefix}api-5xx-rate"
  alarm_description   = "[P1] A2 API の 5xx の割合（5分間で5%を超える。リクエストが10件以上のとき）。影響：一部またはすべての操作が失敗している。最初に見るもの：${local.logs_insights_hint}（log.level = ERROR の error_code 別の件数）。戻し方：直前のリリースで増えたなら、前の版でデプロイし直す"
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 5
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions.P1
  ok_actions          = local.alarm_actions.P1

  metric_query {
    id          = "rate"
    expression  = "IF(requests >= 10, 100 * (FILL(target5xx, 0) + FILL(elb5xx, 0)) / requests, 0)"
    label       = "5xx の割合（%）"
    return_data = true
  }

  metric_query {
    id = "requests"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      dimensions  = { LoadBalancer = aws_lb.this.arn_suffix }
      period      = 300
      stat        = "Sum"
    }
  }

  metric_query {
    id = "target5xx"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      dimensions  = { LoadBalancer = aws_lb.this.arn_suffix }
      period      = 300
      stat        = "Sum"
    }
  }

  metric_query {
    id = "elb5xx"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_ELB_5XX_Count"
      dimensions  = { LoadBalancer = aws_lb.this.arn_suffix }
      period      = 300
      stat        = "Sum"
    }
  }
}

# A4：応答時間の p95
resource "aws_cloudwatch_metric_alarm" "api_latency_p95" {
  alarm_name          = "${local.alarm_prefix}api-latency-p95"
  alarm_description   = "[P2] A4 API の応答時間（p95）が10分間 1秒を超える。影響：操作が遅い（非機能要件の「1秒以内」を満たしていない）。最初に見るもの：${local.logs_insights_hint}（duration_ms の大きい url.path）、RDS の CPU、API のタスクの CPU"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = local.alb_dimensions
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions.P2
  ok_actions          = local.alarm_actions.P2
}

# A8：RDS の空き容量（割り当ての 20%）
resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${local.alarm_prefix}rds-free-storage"
  alarm_description   = "[P2] A8 RDS の空き容量が ${aws_db_instance.this.allocated_storage}GB の 20% を下回った。影響：いっぱいになると書き込みができなくなる。最初に見るもの：テーブルの大きさ（pg_total_relation_size）。対処：allocated_storage を増やす"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.this.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = aws_db_instance.this.allocated_storage * 0.2 * 1024 * 1024 * 1024
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions.P2
  ok_actions          = local.alarm_actions.P2
}

# A9：API のタスクの CPU・メモリ
resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  alarm_name          = "${local.alarm_prefix}api-cpu"
  alarm_description   = "[P2] A9 API のタスクの CPU が10分間 80% を超える。影響：応答が遅くなる。最初に見るもの：アクセスの量、${local.logs_insights_hint}（重いパス）。対処：backend_cpu を増やす"
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  dimensions          = local.ecs_dimensions
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions.P2
  ok_actions          = local.alarm_actions.P2
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory" {
  alarm_name          = "${local.alarm_prefix}api-memory"
  alarm_description   = "[P2] A9 API のタスクのメモリが10分間 90% を超える。影響：メモリが足りなくなると、タスクが止まって作り直される（その間 API が使えない）。最初に見るもの：ECS のサービスのイベント（OutOfMemory で止まっていないか）。対処：backend_memory を増やす"
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  dimensions          = local.ecs_dimensions
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = 90
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions.P2
  ok_actions          = local.alarm_actions.P2
}
