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
    # ALB・ターゲットグループの名前（32文字まで）に「-backend-tg」などを付けても収まる長さにする
    condition     = can(regex("^[a-z][a-z0-9-]{1,18}$", var.project_name))
    error_message = "project_name は英小文字で始まる、英小文字・数字・ハイフンの2〜19文字にしてください。"
  }
}

variable "backend_cpu" {
  description = "API（Spring Boot）の Fargate のタスクの CPU（1024 = 1 vCPU）"
  type        = number
  default     = 512

  validation {
    condition     = contains([256, 512, 1024], var.backend_cpu)
    error_message = "backend_cpu は 256・512・1024 のどれかにしてください。"
  }
}

variable "backend_memory" {
  description = "API（Spring Boot）の Fargate のタスクのメモリ（MiB）。Java はこの 75% までを使う（backend/Dockerfile）"
  type        = number
  default     = 1024

  validation {
    # Spring Boot は 512MiB では足りないことがあるので、1GiB 以上にする。CPU との組み合わせは Fargate の決まりに従う
    condition     = var.backend_memory >= 1024 && var.backend_memory <= 4096
    error_message = "backend_memory は 1024〜4096（MiB）にしてください。"
  }
}

variable "backend_cpu_architecture" {
  description = "API のコンテナの CPU の種類。イメージを作る PC と合わせる（Apple シリコンの Mac なら ARM64。ARM64 は X86_64 より約2割安い）"
  type        = string
  default     = "ARM64"

  validation {
    condition     = contains(["ARM64", "X86_64"], var.backend_cpu_architecture)
    error_message = "backend_cpu_architecture は ARM64 か X86_64 にしてください。"
  }
}

variable "log_retention_days" {
  description = "API のログ（CloudWatch Logs）を残す日数"
  type        = number
  default     = 14

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90], var.log_retention_days)
    error_message = "log_retention_days は 1・3・5・7・14・30・60・90 のどれかにしてください。"
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
    # RDS で使えない文字（/ @ " と空白）に加え、シェル・URL で特別な意味を持つ文字（引用符・\ など）も使わない
    condition     = can(regex("^[A-Za-z0-9_-]{12,}$", var.db_password))
    error_message = "db_password は12文字以上で、英数字・ハイフン・アンダースコアだけにしてください（例: openssl rand -hex 16 の結果）。"
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
