# --- ALB ---
# パスで2つのターゲットグループに振り分ける（/api/* → バックエンド、それ以外 → フロントエンド）。
# ブラウザからは CloudFront の1つのドメインに見えるため、CORS の設定はいらない

# CloudFront だけが知っている値。CloudFront がオリジン（ALB）へのリクエストにヘッダーで付け、ALB はこの値が付いたものだけを通す。
# セキュリティグループの CloudFront のプレフィックスリストは「どこかの CloudFront」なので、ほかの人の CloudFront から来たものはここで断る。
# 値は state にだけ残る（state は Git に入れない）
resource "random_password" "origin_verify" {
  length  = 32
  special = false
}

locals {
  origin_verify_header = "X-Origin-Verify"
}

resource "aws_lb" "this" {
  name               = "${var.project_name}-alb"
  load_balancer_type = "application"
  internal           = false
  subnets            = local.alb_subnet_ids
  security_groups    = [aws_security_group.alb.id]

  # 形の正しくないヘッダーは、ターゲットに渡す前に捨てる
  drop_invalid_header_fields = true
  # 学習用の環境のため、使わない期間は terraform destroy で ALB ごと削除する（止める機能がなく、存在するだけで課金されるため）
  enable_deletion_protection = false

  tags = {
    Name = "${var.project_name}-alb"
  }
}

resource "aws_lb_target_group" "frontend" {
  name                 = "${var.project_name}-frontend-tg"
  port                 = 80
  protocol             = "HTTP"
  target_type          = "instance"
  vpc_id               = data.aws_vpc.default.id
  deregistration_delay = 30

  health_check {
    path                = "/"
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "${var.project_name}-frontend-tg"
  }
}

resource "aws_lb_target_group" "backend" {
  name                 = "${var.project_name}-backend-tg"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "instance"
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

resource "aws_lb_target_group_attachment" "frontend" {
  target_group_arn = aws_lb_target_group.frontend.arn
  target_id        = aws_instance.frontend.id
  port             = 80
}

resource "aws_lb_target_group_attachment" "backend" {
  target_group_arn = aws_lb_target_group.backend.arn
  target_id        = aws_instance.backend.id
  port             = 8080
}

# HTTP 80番。HTTPS はブラウザと CloudFront の間で使い、CloudFront から ALB までは AWS の中を HTTP で通る
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  # 秘密のヘッダーが付いていない（CloudFront を通っていない）リクエストは断る
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

  condition {
    http_header {
      http_header_name = local.origin_verify_header
      values           = [random_password.origin_verify.result]
    }
  }
}

resource "aws_lb_listener_rule" "frontend" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 20

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }

  condition {
    http_header {
      http_header_name = local.origin_verify_header
      values           = [random_password.origin_verify.result]
    }
  }
}
