# --- 性能テスト（docs/performance-test-plan.md「3. 実行する環境」の段階 2。Issue #175）---
# RDS は VPC の中にあり、手元からは届かない。そこで、DB を操作する一度きりのタスク（PostgreSQL 17 の公式イメージの psql）を
# VPC の中で動かす（perf/lib/aws-psql.sh が run-task で起動し、SQL はコマンドの引数で渡す）。
# このタスクの定義は、置いておくだけなら費用がかからないので、perf_test にかかわらず作っておく
# （sns_perf を作ってから API を perf_test = true でつなぎ替える順番にするため）

locals {
  perf_db_name = "sns_perf"
}

resource "aws_ecs_task_definition" "perf_db" {
  family                   = "${var.project_name}-perf-db"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  # 秘密の値（DB のパスワード）を読む権限は、API の実行ロールと同じものを使う
  execution_role_arn = aws_iam_role.backend_execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.backend_cpu_architecture
  }

  container_definitions = jsonencode([{
    name      = "psql"
    image     = "postgres:17"
    essential = true
    # 実行する内容は、run-task のコマンドの上書きで渡す（sh -c "<スクリプト>"）
    entryPoint = ["sh", "-c"]
    command    = ["psql --version"]

    environment = [
      { name = "PGHOST", value = aws_db_instance.this.address },
      { name = "PGPORT", value = tostring(aws_db_instance.this.port) },
      { name = "PGUSER", value = aws_db_instance.this.username },
      { name = "PGDATABASE", value = local.perf_db_name },
    ]

    secrets = [
      { name = "PGPASSWORD", valueFrom = aws_ssm_parameter.db_password.arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.backend.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "perf-db"
      }
    }
  }])

  tags = {
    Name = "${var.project_name}-perf-db"
  }
}
