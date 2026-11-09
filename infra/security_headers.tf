# --- セキュリティのヘッダー（CloudFront のレスポンスヘッダーポリシー。Issue #160）---
# 画面（S3 の index.html・JS・CSS）には、S3 がヘッダーを付けないので、CloudFront で付ける。
# API にも同じポリシーを付ける（Spring も X-Frame-Options などを付けるが、HSTS は HTTP で受けているので付かない）。
# 詳しくは docs/infrastructure.md「セキュリティのヘッダー」

locals {
  # 画面の CSP。読み込んでよい場所を、自分のドメイン（'self'）と画像の CloudFront だけに絞る。
  # - script-src・style-src に 'unsafe-inline' を入れない（index.html にインラインのスクリプト・スタイルはない。React の style 属性は DOM の API で入るので許可はいらない）
  # - img-src の blob: は、投稿・アイコンの画像を選んだときのプレビュー（URL.createObjectURL）のため
  # - frame-ancestors 'none'：ほかのサイトの枠（iframe）に埋め込ませない（クリックジャッキング対策。X-Frame-Options: DENY と同じ）
  app_content_security_policy = join("; ", [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' blob: https://${aws_cloudfront_distribution.images.domain_name}",
    "connect-src 'self'",
    "font-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ])

  # 画像の CSP。画像の URL をブラウザで直接開いたときに、万一中身が HTML や SVG でもスクリプトを動かさない（sandbox）。
  # style-src 'unsafe-inline' は、ブラウザが画像を表示するときに自分で付けるスタイルのため（画像の中身からは使えない）
  images_content_security_policy = "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox"
}

resource "aws_cloudfront_response_headers_policy" "app" {
  name    = "${var.project_name}-app-security-headers"
  comment = "Security headers for the app (frontend and API)"

  security_headers_config {
    # HTTPS だけで接続させる（1年）。cloudfront.net のサブドメインはほかの人のものなので、includeSubDomains・preload は付けない
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = false
      preload                    = false
      override                   = true
    }

    content_security_policy {
      content_security_policy = local.app_content_security_policy
      override                = true
    }

    frame_options {
      frame_option = "DENY"
      override     = true
    }

    content_type_options {
      override = true
    }

    # ほかのサイトへ移るときは、URL のパス（投稿 ID など）を送らず、ドメインだけを送る
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
  }

  # 使わないブラウザの機能（カメラ・マイク・位置情報など）を、このサイトでは使えないようにする
  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
      override = true
    }
  }
}

resource "aws_cloudfront_response_headers_policy" "images" {
  name    = "${var.project_name}-images-security-headers"
  comment = "Security headers for uploaded images"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = false
      preload                    = false
      override                   = true
    }

    content_security_policy {
      content_security_policy = local.images_content_security_policy
      override                = true
    }

    # Content-Type（image/png など）と違う種類として解釈させない
    content_type_options {
      override = true
    }

    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
  }
}
