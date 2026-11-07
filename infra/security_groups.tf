# --- セキュリティグループ ---
# 送信元は IP アドレスではなく、できるだけセキュリティグループ単位で絞る（docs/infrastructure.md「セキュリティグループ」）。
# インターネットから直接受け付けるものはない。ALB も CloudFront からだけ受け付ける

# CloudFront がオリジン（ALB）へ接続するときの送信元 IP の一覧（AWS が管理する）
data "aws_ec2_managed_prefix_list" "cloudfront_origin_facing" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "alb" {
  name        = "${var.project_name}-alb-sg"
  description = "ALB: HTTP(80) from CloudFront only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    # CloudFront 以外からの接続は受け付けない。ほかの人の CloudFront からの接続は、リスナーで秘密のヘッダーを見て断る（alb.tf）
    description     = "HTTP from CloudFront only"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront_origin_facing.id]
  }

  egress {
    description = "To the API tasks (8080) and their health checks inside the VPC"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [data.aws_vpc.default.cidr_block]
  }

  tags = {
    Name = "${var.project_name}-alb-sg"
  }
}

resource "aws_security_group" "backend" {
  name        = "${var.project_name}-backend-sg"
  description = "Backend (Fargate): Spring Boot(8080) from the ALB SG only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Spring Boot API from the ALB only"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    # ECR からイメージを取る・ログを送る・SSM から秘密の値を読む・S3 に画像を置く（どれも AWS の公開の URL）・RDS（VPC の中）
    description = "All outbound (ECR, CloudWatch Logs, SSM, S3, RDS)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-backend-sg"
  }
}

resource "aws_security_group" "rds" {
  name        = "${var.project_name}-rds-sg"
  description = "RDS: PostgreSQL(5432) from the backend SG only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "PostgreSQL from the API tasks only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.backend.id]
  }

  tags = {
    Name = "${var.project_name}-rds-sg"
  }
}
