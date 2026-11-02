# --- RDS（PostgreSQL 17）---
# インターネットに公開せず、バックエンドの EC2 からだけ接続する。テーブルは Spring Boot の起動時に Flyway が作る

# DB サブネットグループは（シングル AZ でも）異なる AZ の複数のサブネットが必要
resource "aws_db_subnet_group" "this" {
  name       = "${var.project_name}-db-subnet-group"
  subnet_ids = data.aws_subnets.default.ids

  tags = {
    Name = "${var.project_name}-db-subnet-group"
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.project_name}-db"
  engine         = "postgres"
  engine_version = "17"

  instance_class    = var.rds_instance_class
  allocated_storage = 20
  storage_type      = "gp2"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false
  # バックエンドの EC2 と同じアベイラビリティゾーンに置く（AZ をまたぐ通信の料金と遅れを避ける）
  availability_zone = local.primary_az

  # 学習用の環境のため、自動バックアップは取らず、削除のときの最終スナップショットも残さない（無料枠を超えないように）。
  # データを残したいときは、terraform destroy の前に手でスナップショットを取る（docs/infrastructure.md）
  backup_retention_period = 0
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true

  tags = {
    Name = "${var.project_name}-db"
  }
}
