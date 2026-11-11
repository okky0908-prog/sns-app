# --- API（Spring Boot）を ECS Fargate で動かす ---
# イメージは infra/deploy.sh が作って ECR に送り（タグ latest）、ECS のサービスを更新して入れ替える。
# サーバー（EC2）は持たない。OS の更新・SSH・自動停止は要らない

locals {
  backend_name = "${var.project_name}-backend"
  # 秘密の値を置く SSM パラメータストアの場所
  ssm_prefix = "/${var.project_name}"
  # JWT の署名鍵。Terraform では作らず、deploy.sh が初回だけ作る（state にも手元にも値を残さないため。docs/infrastructure.md）
  jwt_secret_parameter_arn = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_prefix}/jwt-secret"
}

# --- コンテナのイメージの置き場所（ECR）---

resource "aws_ecr_repository" "backend" {
  name = local.backend_name
  # deploy.sh は毎回 latest を付け直して送る（ECS は入れ替えのときに、その時点の latest の中身を使う）
  image_tag_mutability = "MUTABLE"
  # 学習用の環境のため、イメージが残っていても terraform destroy で削除できるようにする
  force_delete = true

  # 送ったときに、既知の脆弱性がないかを調べる（無料の基本スキャン）
  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = local.backend_name
  }
}

# 古いイメージは5つまで残し、それより古いものは消す（保存の料金を増やさないため）
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 5 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}

# --- 秘密の値（SSM パラメータストア。標準のパラメータは無料）---
# タスクの起動時に ECS が読み、環境変数としてコンテナに渡す。タスク定義やログには値が出ない

resource "aws_ssm_parameter" "db_password" {
  name        = "${local.ssm_prefix}/db-password"
  description = "RDS の管理者パスワード（terraform.tfvars の db_password）"
  type        = "SecureString"
  value       = var.db_password
}

# --- ログ（CloudWatch Logs）---

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.backend_name}"
  retention_in_days = var.log_retention_days
}

# --- IAM ロール ---

data "aws_iam_policy_document" "ecs_tasks_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# タスクの実行ロール：ECS がタスクを起動するために使う（イメージの取得・ログの送信・秘密の値の読み取り）
resource "aws_iam_role" "backend_execution" {
  name               = "${local.backend_name}-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

resource "aws_iam_role_policy_attachment" "backend_execution" {
  role       = aws_iam_role.backend_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# 読めるのは、このアプリの2つの値だけ（AWS が管理する鍵 aws/ssm で暗号化しているので、KMS の権限は要らない）
data "aws_iam_policy_document" "backend_execution_secrets" {
  statement {
    actions   = ["ssm:GetParameters"]
    resources = [aws_ssm_parameter.db_password.arn, local.jwt_secret_parameter_arn]
  }
}

resource "aws_iam_role_policy" "backend_execution_secrets" {
  name   = "${local.backend_name}-secrets"
  role   = aws_iam_role.backend_execution.id
  policy = data.aws_iam_policy_document.backend_execution_secrets.json
}

# タスクロール：アプリ（Spring Boot）が AWS を使うための権限。アクセスキーをコンテナに置かず、このロールから一時的な認証情報を受け取る
# （Spring Boot の S3Config は DefaultCredentialsProvider を使うので、設定はいらない）
resource "aws_iam_role" "backend_task" {
  name               = "${local.backend_name}-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

data "aws_iam_policy_document" "backend_task" {
  # アプリが使うのは PutObject（ImageStorage.upload）と DeleteObject（ImageStorage.delete）だけ。このバケットの中だけに絞る
  statement {
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.images.arn}/*"]
  }

  # 調べものをするときに、動いているコンテナに入れるようにする（ECS Exec。SSH の代わり）
  statement {
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "backend_task" {
  name   = "${local.backend_name}-task"
  role   = aws_iam_role.backend_task.id
  policy = data.aws_iam_policy_document.backend_task.json
}

# --- ECS ---

resource "aws_ecs_cluster" "this" {
  name = var.project_name
}

resource "aws_ecs_task_definition" "backend" {
  family                   = local.backend_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory
  execution_role_arn       = aws_iam_role.backend_execution.arn
  task_role_arn            = aws_iam_role.backend_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.backend_cpu_architecture
  }

  container_definitions = jsonencode([{
    name      = "backend"
    image     = "${aws_ecr_repository.backend.repository_url}:latest"
    essential = true

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    # 本番用の設定（backend/src/main/resources/application-production.yml が読む）。プロファイルは Dockerfile で指定している
    # 性能テストの間（perf_test = true）だけ、専用の DB（sns_perf）につなぎ、metrics を公開する（perf.tf）
    environment = concat([
      { name = "DB_HOST", value = aws_db_instance.this.address },
      { name = "POSTGRES_PORT", value = tostring(aws_db_instance.this.port) },
      { name = "POSTGRES_DB", value = var.perf_test ? local.perf_db_name : aws_db_instance.this.db_name },
      { name = "POSTGRES_USER", value = aws_db_instance.this.username },
      # Cookie に Secure 属性を付ける（ブラウザと CloudFront の間は HTTPS）
      { name = "REFRESH_TOKEN_COOKIE_SECURE", value = "true" },
      { name = "S3_BUCKET", value = aws_s3_bucket.images.bucket },
      { name = "AWS_REGION", value = var.aws_region },
      # 空なら AWS の S3 に、タスクロールの権限で接続する（ローカルの LocalStack 用の設定を使わない）
      { name = "S3_ENDPOINT", value = "" },
      { name = "IMAGE_BASE_URL", value = "https://${aws_cloudfront_distribution.images.domain_name}" },
      ], var.perf_test ? [
      { name = "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", value = "health,metrics" },
    ] : [])

    secrets = [
      { name = "POSTGRES_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn },
      { name = "JWT_SECRET", valueFrom = local.jwt_secret_parameter_arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.backend.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "backend"
      }
    }

    # 止めるとき（入れ替え・タスクを 0 にしたとき）に、処理中のリクエストを終えるまで待つ時間
    stopTimeout = 30
  }])

  lifecycle {
    precondition {
      # Fargate で選べる CPU とメモリの組み合わせ（256 → 2GiB まで、512 → 4GiB まで、1024 → 2GiB から）
      condition = (
        (var.backend_cpu == 256 && var.backend_memory <= 2048) ||
        (var.backend_cpu == 512 && var.backend_memory <= 4096) ||
        (var.backend_cpu == 1024 && var.backend_memory >= 2048)
      )
      error_message = "backend_cpu と backend_memory の組み合わせを Fargate では使えません（256 は 2048 まで、512 は 4096 まで、1024 は 2048 以上）。"
    }
  }

  tags = {
    Name = local.backend_name
  }
}

resource "aws_ecs_service" "backend" {
  name            = local.backend_name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.backend.arn
  launch_type     = "FARGATE"
  # 作った直後は 0（イメージと JWT の署名鍵がまだないため）。deploy.sh が 1 にし、servers.sh が 1／0 を切り替える
  desired_count = 0

  # 入れ替えのときは、新しいタスクが ALB のヘルスチェックに通ってから古いタスクを止める（止まる時間をなくす）
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  # 新しいタスクが起動に失敗し続けたら、入れ替えをやめて前の版に戻す
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  # Spring Boot の起動（Flyway を含む）を待つ間は、ヘルスチェックの失敗でタスクを止めない
  health_check_grace_period_seconds = 180
  enable_execute_command            = true

  network_configuration {
    # RDS と同じアベイラビリティゾーンのパブリックサブネットに置く。公開IP はイメージの取得・S3 への接続に使う
    # （プライベートサブネットに置くと NAT ゲートウェイが必要になり、費用がかかるため）。
    # 受け付けるのは ALB からだけ（セキュリティグループ）
    subnets          = [data.aws_subnet.primary.id]
    security_groups  = [aws_security_group.backend.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = 8080
  }

  lifecycle {
    # タスクの数は deploy.sh・servers.sh が変える。terraform apply で 0 に戻さない
    ignore_changes = [desired_count]
  }

  # ALB のリスナーができてから作る（ターゲットグループが ALB につながっていないと作れない）
  depends_on = [aws_lb_listener_rule.backend]

  tags = {
    Name = local.backend_name
  }
}
