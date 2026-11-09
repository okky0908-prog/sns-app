# --- ALB ---
# API（Fargate）の前に置く。CloudFront は /api/* だけをここへ送る（画面は S3 から配る。cloudfront_app.tf）。
# Fargate のタスクは入れ替えのたびに IP が変わるので、決まった名前で受け付ける入口として ALB を使う。
# 内部用（internal）にしてプライベートサブネットに置き、インターネットからは届かないようにする。
# CloudFront は VPC オリジン（cloudfront_app.tf）で、インターネットを通らずに VPC の中のこの ALB へ届く（Issue #161）

resource "aws_lb" "this" {
  name               = "${var.project_name}-alb"
  load_balancer_type = "application"
  internal           = true
  subnets            = aws_subnet.private[*].id
  security_groups    = [aws_security_group.alb.id]

  # 形の正しくないヘッダーは、ターゲットに渡す前に捨てる
  drop_invalid_header_fields = true
  # 学習用の環境のため、使わない期間は terraform destroy で ALB ごと削除する（止める機能がなく、存在するだけで課金されるため）
  enable_deletion_protection = false

  tags = {
    Name = "${var.project_name}-alb"
  }
}

resource "aws_lb_target_group" "backend" {
  name     = "${var.project_name}-backend-tg"
  port     = 8080
  protocol = "HTTP"
  # Fargate のタスク（awsvpc）は IP で登録する。登録と解除は ECS のサービスが行う（ecs.tf）
  target_type          = "ip"
  vpc_id               = data.aws_vpc.default.id
  deregistration_delay = 30

  # Spring Boot Actuator の health（application.yml で /api/health に公開している。ログインなしで見られる）
  health_check {
    path                = "/api/health"
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "${var.project_name}-backend-tg"
  }
}

# HTTP 80番。HTTPS はブラウザと CloudFront の間で使い、CloudFront から ALB までは VPC オリジンで、AWS の内部のネットワークを HTTP で通る
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  # /api/* 以外は断る（CloudFront は /api/* だけを送る）
  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "backend" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }

  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }
}
