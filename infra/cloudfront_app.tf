# --- アプリ用の CloudFront（HTTPS の入口）---
# 本番はリフレッシュトークンの Cookie に Secure 属性を付けるため、HTTPS が必須（docs/infrastructure.md「HTTPS」）。
# 独自ドメインがなくても、CloudFront の URL（https://xxxx.cloudfront.net）なら無料の証明書で HTTPS にできる。
# パスで振り分ける：/api/* → VPC オリジン → 内部用の ALB → Fargate（HTTP）、それ以外 → 画面の S3（OAC）。
# ブラウザからは1つのドメインに見えるため、CORS の設定はいらない

# キャッシュしない（Managed-CachingDisabled）。API はログインした人ごとに中身が違うため。
# index.html も、デプロイしたらすぐ新しい版を返したいのでキャッシュしない（中で読む JS・CSS のファイル名が変わる）
data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# ブラウザから届いたヘッダー・Cookie・検索条件を、すべてそのまま ALB に送る（Managed-AllViewer）。
# これを使わないと、CloudFront は GET の Authorization ヘッダー（アクセストークン）と Cookie（リフレッシュトークン）を取り除いてしまう
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

# CloudFront から VPC の中の ALB へ届くための口（CloudFront が ALB のサブネットにネットワークの口（ENI）を作る）。
# 自分のアカウントの CloudFront だけが使えるので、ほかの人の CloudFront からは ALB に届かない
resource "aws_cloudfront_vpc_origin" "api" {
  vpc_origin_endpoint_config {
    name                   = "${var.project_name}-api-alb"
    arn                    = aws_lb.this.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }

  tags = {
    Name = "${var.project_name}-api-vpc-origin"
  }
}

resource "aws_cloudfront_distribution" "app" {
  enabled             = true
  comment             = "${var.project_name} app (frontend on S3 and API on Fargate)"
  price_class         = var.cloudfront_price_class
  is_ipv6_enabled     = true
  default_root_object = "index.html"

  # 画面（React のビルド結果）
  origin {
    origin_id                = "frontend-s3"
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  # API。VPC オリジンで、インターネットを通らずに VPC の中の内部用の ALB へ届く（Issue #161）
  origin {
    origin_id   = "api-alb"
    domain_name = aws_lb.this.dns_name

    vpc_origin_config {
      vpc_origin_id            = aws_cloudfront_vpc_origin.api.id
      origin_read_timeout      = 30
      origin_keepalive_timeout = 5
    }
  }

  # 画面。画面の URL は index.html に書き換えて S3 から返す（frontend.tf の CloudFront Functions）
  default_cache_behavior {
    target_origin_id           = "frontend-s3"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_disabled.id
    compress                   = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.app.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite.arn
    }
  }

  # API。すべてのメソッドを ALB へ送り、キャッシュしない
  ordered_cache_behavior {
    path_pattern               = "/api/*"
    target_origin_id           = "api-alb"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer.id
    compress                   = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.app.id
  }

  # Vite がビルドした JS・CSS（ファイル名に中身のハッシュが付く）は、長くキャッシュしてよい
  ordered_cache_behavior {
    path_pattern               = "/assets/*"
    target_origin_id           = "frontend-s3"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress                   = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.app.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = {
    Name = "${var.project_name}-app-cdn"
  }
}
