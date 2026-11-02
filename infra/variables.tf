variable "aws_region" {
  description = "リソースを作成するAWSリージョン"
  type        = string
  default     = "ap-northeast-1"
}

variable "project_name" {
  description = "リソース名のプレフィックス"
  type        = string
  default     = "sns-app"

  validation {
    # ALB・ターゲットグループの名前（32文字まで）に「-frontend-tg」を付けても収まる長さにする
    condition     = can(regex("^[a-z][a-z0-9-]{1,18}$", var.project_name))
    error_message = "project_name は英小文字で始まる、英小文字・数字・ハイフンの2〜19文字にしてください。"
  }
}

variable "instance_type" {
  description = "EC2のインスタンスタイプ（2台とも同じ。無料利用枠の対象に限る）"
  type        = string
  default     = "t3.micro"

  validation {
    condition     = contains(["t2.micro", "t3.micro"], var.instance_type)
    error_message = "instance_type は無料利用枠の対象（t2.micro / t3.micro）にしてください。"
  }
}

variable "rds_instance_class" {
  description = "RDSのインスタンスクラス（シングルAZ。無料利用枠の対象に限る）"
  type        = string
  default     = "db.t3.micro"

  validation {
    condition     = contains(["db.t3.micro", "db.t4g.micro"], var.rds_instance_class)
    error_message = "rds_instance_class は無料利用枠の対象（db.t3.micro / db.t4g.micro）にしてください。"
  }
}

variable "cloudfront_price_class" {
  description = "CloudFrontの価格クラス。PriceClass_200 は日本を含み、南米・オーストラリアなどを除く"
  type        = string
  default     = "PriceClass_200"

  validation {
    condition     = contains(["PriceClass_100", "PriceClass_200"], var.cloudfront_price_class)
    error_message = "cloudfront_price_class は PriceClass_100 か PriceClass_200 にしてください。"
  }
}

variable "ssh_public_key" {
  description = "EC2にSSH接続するための公開鍵の中身（例: ~/.ssh/sns-app-aws-ec2.pub の内容）"
  type        = string
}

variable "my_ip_cidr" {
  description = "SSH（22番）を許可する送信元（自分のグローバルIP/32）。アプリ（HTTPS）は CloudFront から誰でも使える"
  type        = string

  validation {
    condition     = can(cidrhost(var.my_ip_cidr, 0)) && endswith(var.my_ip_cidr, "/32")
    error_message = "my_ip_cidr は「自分のグローバルIP/32」の形で指定してください（例: 203.0.113.1/32）。"
  }
}

variable "db_name" {
  description = "RDSに作成するデータベース名"
  type        = string
  default     = "sns"
}

variable "db_username" {
  description = "RDSの管理者ユーザー名"
  type        = string
  default     = "sns"
}

variable "db_password" {
  description = "RDSの管理者パスワード（terraform.tfvarsで設定する）"
  type        = string
  sensitive   = true

  validation {
    # RDS で使えない文字（/ @ " と空白）に加え、backend.env（systemd が読む）で特別な意味を持つ文字（引用符・\ など）も使わない
    condition     = can(regex("^[A-Za-z0-9_-]{12,}$", var.db_password))
    error_message = "db_password は12文字以上で、英数字・ハイフン・アンダースコアだけにしてください（例: openssl rand -hex 16 の結果）。"
  }
}

variable "auto_stop_time" {
  description = "EC2を毎日自動で停止する時刻（日本時間、HH:MM）。止め忘れで無料利用枠（月750時間）を超えないようにする"
  type        = string
  default     = "00:00"

  validation {
    condition     = can(regex("^([01][0-9]|2[0-3]):[0-5][0-9]$", var.auto_stop_time))
    error_message = "auto_stop_time は HH:MM の形で指定してください（例: 00:00）。"
  }
}

variable "monthly_budget_usd" {
  description = "請求アラートの月の予算（米ドル）。見込みがこれを超えそう、または実際の料金が80%を超えたらメールで知らせる"
  type        = number
  default     = 30

  validation {
    condition     = var.monthly_budget_usd > 0
    error_message = "monthly_budget_usd は0より大きい値にしてください。"
  }
}

variable "budget_alert_email" {
  description = "請求アラートを受け取るメールアドレス"
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.budget_alert_email))
    error_message = "budget_alert_email はメールアドレスの形で指定してください。"
  }
}
