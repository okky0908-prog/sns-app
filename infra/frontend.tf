# --- 画面（React のビルド結果）を置く S3 と、画面の URL の書き換え ---
# infra/deploy.sh が frontend/dist を S3 に送る。S3 は非公開にし、アプリ用の CloudFront（OAC）からだけ読めるようにする。
# nginx の try_files（ない URL には index.html を返す）の代わりに、CloudFront Functions で URL を書き換える

resource "aws_s3_bucket" "frontend" {
  bucket = "${var.project_name}-frontend-${data.aws_caller_identity.current.account_id}"
  # ビルド結果はいつでも作り直せるので、ファイルが残っていても terraform destroy で削除できるようにする
  force_destroy = true

  tags = {
    Name = "${var.project_name}-frontend"
  }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${var.project_name}-frontend-oac"
  description                       = "CloudFront reads the frontend bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# アプリ用の CloudFront（自分のディストリビューションだけ）に、読み取りだけを許可する
data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    sid       = "AllowCloudFrontReadOnly"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.app.arn]
    }
  }

  # 一覧を見る権限。これがないと、S3 はないファイルを「ない（404）」と教えず 403 を返す（Issue #165）。
  # CloudFront は検索条件（?list-type=2 など）を S3 に送らないので、外からバケットの一覧は取れない
  statement {
    sid       = "AllowCloudFrontToReturn404"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.frontend.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.app.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.frontend]
}

# 画面の URL（/users/yamada など）を /index.html に書き換える（cloudfront_functions/spa_rewrite.js。テストは同じフォルダ）
resource "aws_cloudfront_function" "spa_rewrite" {
  name    = "${var.project_name}-spa-rewrite"
  runtime = "cloudfront-js-2.0"
  comment = "Rewrite app routes to /index.html"
  publish = true
  code    = file("${path.module}/cloudfront_functions/spa_rewrite.js")
}
