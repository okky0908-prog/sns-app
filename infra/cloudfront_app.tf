# --- アプリ用の CloudFront（HTTPS の入口）---
# 本番はリフレッシュトークンの Cookie に Secure 属性を付けるため、HTTPS が必須（docs/infrastructure.md「HTTPS」）。
# 独自ドメインがなくても、CloudFront の URL（https://xxxx.cloudfront.net）なら無料の証明書で HTTPS にできる。
# ブラウザと CloudFront の間は HTTPS、CloudFront から ALB までは HTTP

# 画面と API はキャッシュしない（ログインした人ごとに中身が違うため。Managed-CachingDisabled）
data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# ブラウザから届いたヘッダー・Cookie・検索条件を、すべてそのまま ALB に送る（Managed-AllViewer）。
# これを使わないと、CloudFront は GET の Authorization ヘッダー（アクセストークン）と Cookie（リフレッシュトークン）を取り除いてしまう
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

resource "aws_cloudfront_distribution" "app" {
  enabled         = true
  comment         = "${var.project_name} app (frontend and API)"
  price_class     = var.cloudfront_price_class
  is_ipv6_enabled = true

  origin {
    origin_id   = "app-alb"
    domain_name = aws_lb.this.dns_name

    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = "http-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = 30
      origin_keepalive_timeout = 5
    }

    # ALB はこのヘッダーが付いたリクエストだけを通す（alb.tf）
    custom_header {
      name  = local.origin_verify_header
      value = random_password.origin_verify.result
    }
  }

  # 画面と API。すべてのメソッドを ALB へ送り、キャッシュしない
  default_cache_behavior {
    target_origin_id         = "app-alb"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
    compress                 = true
  }

  # Vite がビルドした JS・CSS（ファイル名に中身のハッシュが付く）は、長くキャッシュしてよい
  ordered_cache_behavior {
    path_pattern           = "/assets/*"
    target_origin_id       = "app-alb"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress               = true
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
