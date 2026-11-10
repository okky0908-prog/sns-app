# --- 画像の保存（S3）と配信（CloudFront）---
# S3 は非公開にし、読み取りは画像用の CloudFront（OAC）からだけにする。書き込み・削除は API のタスクロール（ecs.tf）だけ。
# アプリ用の CloudFront とは分ける。画面の URL（/posts/:postId）と画像のキー（posts/...）が同じパスになるため

# バケット名は世界で1つにする必要があるので、アカウント ID を付ける
resource "aws_s3_bucket" "images" {
  bucket = "${var.project_name}-images-${data.aws_caller_identity.current.account_id}"
  # 学習用の環境のため、画像が残っていても terraform destroy で削除できるようにする
  force_destroy = true

  tags = {
    Name = "${var.project_name}-images"
  }
}

resource "aws_s3_bucket_public_access_block" "images" {
  bucket = aws_s3_bucket.images.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACL を使わず、バケットの持ち主がすべてのファイルを持つ
resource "aws_s3_bucket_ownership_controls" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_cloudfront_origin_access_control" "images" {
  name                              = "${var.project_name}-images-oac"
  description                       = "CloudFront reads the images bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# AWS が用意しているキャッシュの設定（Managed-CachingOptimized）。画像のキーは UUID で、同じキーの中身は変わらないので長くキャッシュしてよい
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

resource "aws_cloudfront_distribution" "images" {
  enabled         = true
  comment         = "${var.project_name} images"
  price_class     = var.cloudfront_price_class
  is_ipv6_enabled = true
  # ルート（/）を S3 にそのまま送ると、S3 は「バケットの一覧」を返してしまう（下のバケットポリシーで s3:ListBucket を許可しているため）。
  # 存在しないファイル名に置き換えて、404 にする（Issue #165）
  default_root_object = "index.html"

  origin {
    origin_id                = "images-s3"
    domain_name              = aws_s3_bucket.images.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.images.id
  }

  default_cache_behavior {
    target_origin_id           = "images-s3"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress                   = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.images.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # 独自ドメインを使わないので、CloudFront の証明書（*.cloudfront.net）を使う
  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = {
    Name = "${var.project_name}-images-cdn"
  }
}

# CloudFront（自分の画像用のディストリビューションだけ）に、読み取りだけを許可する
data "aws_iam_policy_document" "images_bucket" {
  statement {
    sid       = "AllowCloudFrontReadOnly"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.images.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.images.arn]
    }
  }

  # 一覧を見る権限。これがないと、S3 はないファイルを「ない（404）」と教えず 403 を返す（Issue #165）。
  # CloudFront は検索条件（?list-type=2 など）を S3 に送らないので、外からバケットの一覧は取れない
  statement {
    sid       = "AllowCloudFrontToReturn404"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.images.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.images.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "images" {
  bucket = aws_s3_bucket.images.id
  policy = data.aws_iam_policy_document.images_bucket.json

  # パブリックアクセスのブロックを先に設定してから、ポリシーを付ける
  depends_on = [aws_s3_bucket_public_access_block.images]
}
