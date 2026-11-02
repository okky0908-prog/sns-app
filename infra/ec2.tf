# --- EC2 ---
# フロントエンド（nginx）とバックエンド（Spring Boot）の2台。どちらも同じアベイラビリティゾーンのパブリックサブネットに置く。
# 公開IPは自動で割り当てるもの（SSH とパッケージの取得用。止めている間は課金されない。起動するたびに変わる）。
# HTTP は ALB のセキュリティグループからだけ受け付けるので、公開IPに直接アクセスしてもアプリは見えない

resource "aws_key_pair" "this" {
  key_name   = "${var.project_name}-key"
  public_key = var.ssh_public_key
}

resource "aws_instance" "frontend" {
  ami                         = data.aws_ami.amazon_linux_2023.id
  instance_type               = var.instance_type
  subnet_id                   = data.aws_subnet.primary.id
  vpc_security_group_ids      = [aws_security_group.frontend.id]
  key_name                    = aws_key_pair.this.key_name
  associate_public_ip_address = true
  # OS からのシャットダウン（毎晩の自動停止）で、削除ではなく「停止」にする
  instance_initiated_shutdown_behavior = "stop"

  user_data = templatefile("${path.module}/user_data_frontend.sh", {
    project_name   = var.project_name
    auto_stop_time = var.auto_stop_time
  })
  user_data_replace_on_change = true

  # IMDSv2（トークンを使うメタデータの取得）だけを許可する
  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = true
  }

  tags = {
    Name = "${var.project_name}-frontend"
  }
}

resource "aws_instance" "backend" {
  ami                         = data.aws_ami.amazon_linux_2023.id
  instance_type               = var.instance_type
  subnet_id                   = data.aws_subnet.primary.id
  vpc_security_group_ids      = [aws_security_group.backend.id]
  key_name                    = aws_key_pair.this.key_name
  associate_public_ip_address = true
  iam_instance_profile        = aws_iam_instance_profile.backend.name

  instance_initiated_shutdown_behavior = "stop"

  user_data = templatefile("${path.module}/user_data_backend.sh", {
    project_name   = var.project_name
    auto_stop_time = var.auto_stop_time
  })
  user_data_replace_on_change = true

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = true
  }

  tags = {
    Name = "${var.project_name}-backend"
  }
}

# --- バックエンドの IAM ロール ---
# S3 に画像をアップロード・削除するための権限。アクセスキーをサーバーに置かず、EC2 に付けたロールから一時的な認証情報を受け取る
# （Spring Boot の S3Config は DefaultCredentialsProvider を使うので、設定はいらない）

data "aws_iam_policy_document" "ec2_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backend" {
  name               = "${var.project_name}-backend-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume_role.json
}

# アプリが使うのは PutObject（ImageStorage.upload）と DeleteObject（ImageStorage.delete）だけ。このバケットの中だけに絞る
data "aws_iam_policy_document" "backend_s3" {
  statement {
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.images.arn}/*"]
  }
}

resource "aws_iam_role_policy" "backend_s3" {
  name   = "${var.project_name}-backend-s3"
  role   = aws_iam_role.backend.id
  policy = data.aws_iam_policy_document.backend_s3.json
}

resource "aws_iam_instance_profile" "backend" {
  name = "${var.project_name}-backend-profile"
  role = aws_iam_role.backend.name
}
