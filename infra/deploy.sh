#!/bin/bash
# ローカル（Mac）でバックエンド・フロントエンドをビルドし、AWS にデプロイする（docs/infrastructure.md「デプロイ」）
# Terraform の一部ではない。`terraform apply` で環境を作ってから実行する
#   API   … jar をビルド → コンテナのイメージを作って ECR に送る → ECS のサービスを入れ替える（タスクが 0 なら 1 にする）
#   画面  … frontend/dist をビルド → 画面の S3 に送る
#
# 使い方: infra/deploy.sh
# 必要なもの: terraform・aws（認証済み）・Docker・Java 25（./gradlew が使う）・Node.js（frontend/.nvmrc の版）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
INFRA_DIR="${SCRIPT_DIR}"

tfout() {
  terraform -chdir="${INFRA_DIR}" output -raw "$1"
}

echo "==> Terraform の出力値を取得"
APP_URL="$(tfout app_url)"
ALB_DNS="$(tfout alb_dns_name)"
AWS_REGION="$(tfout aws_region)"
ECR_URL="$(tfout ecr_repository_url)"
CLUSTER="$(tfout ecs_cluster_name)"
SERVICE="$(tfout ecs_service_name)"
LOG_GROUP="$(tfout backend_log_group)"
FRONTEND_BUCKET="$(tfout frontend_bucket)"
JWT_PARAM="$(tfout jwt_secret_parameter_name)"
# ログに入れるリリースの版（docs/observability.md）。コミットしていない変更があれば印を付ける
APP_VERSION="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)$(git -C "${REPO_ROOT}" diff --quiet HEAD || echo '-dirty')"
# イメージの CPU の種類は、タスク定義（Terraform の backend_cpu_architecture）と合わせる
ARCH="$(aws ecs describe-task-definition --region "${AWS_REGION}" \
  --task-definition "$(aws ecs describe-services --region "${AWS_REGION}" --cluster "${CLUSTER}" --services "${SERVICE}" \
    --query 'services[0].taskDefinition' --output text)" \
  --query 'taskDefinition.runtimePlatform.cpuArchitecture' --output text)"
case "${ARCH}" in
  ARM64) PLATFORM="linux/arm64" ;;
  X86_64) PLATFORM="linux/amd64" ;;
  *) echo "タスク定義の CPU の種類が読み取れませんでした（${ARCH}）。" >&2; exit 1 ;;
esac

# --- 秘密の値 ---

echo "==> JWT の署名鍵（SSM パラメータストア）"
# 初回だけ作り、2回目以降は同じ値を使い続ける（変えると、ログイン中の人が全員ログアウトになる）。
# 値は画面にも手元のファイルにも出さない（Terraform の state にも入れない）
if aws ssm get-parameter --region "${AWS_REGION}" --name "${JWT_PARAM}" --query 'Parameter.Name' --output text > /dev/null 2>&1; then
  echo "    すでにあるので、そのまま使う"
else
  aws ssm put-parameter --region "${AWS_REGION}" --name "${JWT_PARAM}" --type SecureString \
    --description "JWT の署名鍵（infra/deploy.sh が作成）" \
    --value "$(openssl rand -base64 48 | tr -d '\n')" > /dev/null
  echo "    作成した"
fi

# --- API ---

echo "==> バックエンドをビルド（./gradlew bootJar）"
(cd "${REPO_ROOT}/backend" && ./gradlew --quiet clean bootJar)

echo "==> コンテナのイメージを作る（${PLATFORM}、版 ${APP_VERSION}）"
# --pull：土台のイメージ（amazoncorretto:25-al2023-headless。Java 25 の最新を指す）を毎回取り直し、セキュリティの修正を取り込む
# --provenance=false --sbom=false：来歴情報を付けず、ふつうの1つのイメージとして送る。付けると、タグがイメージインデックス
#   （本体と来歴情報をまとめたもの）を指し、ECR の基本スキャンの結果をタグで引けない（ScanNotFoundException）
docker build --quiet --pull --provenance=false --sbom=false --platform "${PLATFORM}" --build-arg "APP_VERSION=${APP_VERSION}" \
  -t "${ECR_URL}:${APP_VERSION}" -t "${ECR_URL}:latest" "${REPO_ROOT}/backend" > /dev/null

echo "==> ECR へ送る"
aws ecr get-login-password --region "${AWS_REGION}" | docker login --username AWS --password-stdin "${ECR_URL%%/*}" > /dev/null
docker push --quiet "${ECR_URL}:${APP_VERSION}" > /dev/null
docker push --quiet "${ECR_URL}:latest" > /dev/null

echo "==> イメージの脆弱性スキャンの結果を確かめる（ECR の基本スキャン。OS のパッケージを調べる）"
# 送ったときに ECR が自動でスキャンする（ecs.tf の scan_on_push）。終わるのを待ってから、入れ替えるかを決める。
# CRITICAL が1件でもあれば入れ替えずに止める（ALLOW_CRITICAL_VULNERABILITIES=1 で止めずに進める）。HIGH は件数を知らせる。
# Java のライブラリは基本スキャンでは調べない（Dependabot で更新する。docs/infrastructure.md「コンテナのイメージ」）
ECR_REPO="${ECR_URL#*/}"
# 送った直後はスキャンがまだない（ScanNotFoundException）。aws ecr wait image-scan-complete はそれを失敗として扱うので、自分で待つ（最大5分）
SCAN_STATUS=""
for _ in $(seq 1 60); do
  SCAN_STATUS="$(aws ecr describe-image-scan-findings --region "${AWS_REGION}" --repository-name "${ECR_REPO}" \
    --image-id "imageTag=${APP_VERSION}" --query 'imageScanStatus.status' --output text 2> /dev/null || echo "NOT_FOUND")"
  case "${SCAN_STATUS}" in COMPLETE | FAILED | UNSUPPORTED_IMAGE) break ;; esac
  sleep 5
done
if [ "${SCAN_STATUS}" != "COMPLETE" ] && [ "${ALLOW_CRITICAL_VULNERABILITIES:-0}" != "1" ]; then
  echo "スキャンの結果を確かめられなかったので、入れ替えずに止めました（状態: ${SCAN_STATUS}）。" >&2
  echo "確かめずに進めるときは ALLOW_CRITICAL_VULNERABILITIES=1 infra/deploy.sh で進められます。" >&2
  exit 1
fi
read -r CRITICAL HIGH <<< "$(aws ecr describe-image-scan-findings --region "${AWS_REGION}" --repository-name "${ECR_REPO}" \
  --image-id "imageTag=${APP_VERSION}" \
  --query '[imageScanFindings.findingSeverityCounts.CRITICAL || `0`, imageScanFindings.findingSeverityCounts.HIGH || `0`]' --output text)"
echo "    CRITICAL: ${CRITICAL}件、HIGH: ${HIGH}件"
if [ "${CRITICAL}" -gt 0 ] || [ "${HIGH}" -gt 0 ]; then
  aws ecr describe-image-scan-findings --region "${AWS_REGION}" --repository-name "${ECR_REPO}" --image-id "imageTag=${APP_VERSION}" \
    --query 'imageScanFindings.findings[?severity==`CRITICAL` || severity==`HIGH`].[severity, name, attributes[?key==`package_name`].value | [0], attributes[?key==`package_version`].value | [0]]' \
    --output text | sed 's/^/    /'
  echo "    （直し方：backend/Dockerfile の土台のイメージは deploy.sh が --pull で毎回最新を取るので、修正版が出ていればデプロイし直すだけで直る）"
fi
if [ "${CRITICAL}" -gt 0 ] && [ "${ALLOW_CRITICAL_VULNERABILITIES:-0}" != "1" ]; then
  echo "CRITICAL の脆弱性があるので、入れ替えずに止めました（今動いている版はそのまま）。" >&2
  echo "修正版を待てないときは ALLOW_CRITICAL_VULNERABILITIES=1 infra/deploy.sh で進められます。" >&2
  exit 1
fi

echo "==> ECS のサービスを入れ替える（タスクが 0 なら 1 にする。起動のたびに Flyway がテーブルを作成・更新する）"
# 始めた入れ替えの ID を覚えておき、終わったあとにその結果を見る（前の版に戻されたときは、戻した入れ替えが PRIMARY になるため）
DEPLOYMENT_ID="$(aws ecs update-service --region "${AWS_REGION}" --cluster "${CLUSTER}" --service "${SERVICE}" \
  --desired-count 1 --force-new-deployment --query 'service.deployments[?status==`PRIMARY`].id | [0]' --output text)"

# --- 画面 ---

echo "==> フロントエンドをビルド（API は同じドメインの /api を呼ぶので、URL を埋め込む必要はない）"
(cd "${REPO_ROOT}/frontend" && npm ci --silent && npm run build --silent)

echo "==> 画面を S3 へ送る"
# JS・CSS（ファイル名にハッシュが付く）を先に送り、最後に index.html を送る（途中で古い index.html が新しいファイルを探さないように）。
# 前の版の JS・CSS も消さない（入れ替えの途中で、古い index.html を開いている人が読み込めなくならないように）
# ブラウザのキャッシュ（Cache-Control。docs/infrastructure.md「キャッシュ」）：
#   /assets/*  … ファイル名に中身のハッシュが付き、中身が変わればファイル名も変わるので、1年キャッシュして確かめにも来させない（immutable）。
#   index.html … デプロイしたらすぐ新しい版を読ませるため、毎回変わっていないかを確かめさせる（no-cache）
#   それ以外   … favicon.svg など。ファイル名にハッシュがないので1日
# sync は中身の同じファイルを送り直さず、Cache-Control を付け直せないので、毎回 cp で送る（合わせて数百 KB）
aws s3 cp --region "${AWS_REGION}" "${REPO_ROOT}/frontend/dist/assets/" "s3://${FRONTEND_BUCKET}/assets/" --recursive \
  --cache-control "public, max-age=31536000, immutable" --only-show-errors
aws s3 cp --region "${AWS_REGION}" "${REPO_ROOT}/frontend/dist/" "s3://${FRONTEND_BUCKET}/" --recursive \
  --exclude index.html --exclude 'assets/*' --cache-control "public, max-age=86400" --only-show-errors
aws s3 cp --region "${AWS_REGION}" "${REPO_ROOT}/frontend/dist/index.html" "s3://${FRONTEND_BUCKET}/index.html" \
  --cache-control "no-cache" --only-show-errors

# --- 入れ替えが終わるのを待つ ---

echo "==> 新しいタスクが動き出すのを待つ（最大10分。ALB のヘルスチェックに通ってから古いタスクを止める）"
if ! aws ecs wait services-stable --region "${AWS_REGION}" --cluster "${CLUSTER}" --services "${SERVICE}"; then
  echo "入れ替えが終わりませんでした。最近のログ：" >&2
  aws logs tail "${LOG_GROUP}" --region "${AWS_REGION}" --since 15m >&2 || true
  exit 1
fi
# 始めた入れ替えの「状態 結果」（例: PRIMARY COMPLETED）。前の版に戻されると PRIMARY でなくなるか、一覧から消える（None）。
# タスクの数がそろってから結果が COMPLETED になるまで少し遅れるので、IN_PROGRESS の間は待つ（最大5分。Issue #178）
deployment_rollout() {
  aws ecs describe-services --region "${AWS_REGION}" --cluster "${CLUSTER}" --services "${SERVICE}" \
    --query "services[0].deployments[?id=='${DEPLOYMENT_ID}'].[status,rolloutState] | [0]" --output text | tr '\t' ' '
}
for _ in $(seq 1 30); do
  ROLLOUT="$(deployment_rollout)"
  [ "${ROLLOUT}" != "PRIMARY IN_PROGRESS" ] && break
  sleep 10
done
# servers.sh stop で止めていた「API の正常なタスクがない」アラームの通知を戻す。
# アラームが ALARM（止めていた間の状態）から戻るのを待ってから戻す（先に戻すと「OK に戻った」メールだけが届くため）
HEALTH_ALARM="$(tfout api_health_alarm_name)"
if [ "$(aws cloudwatch describe-alarms --region "${AWS_REGION}" --alarm-names "${HEALTH_ALARM}" --query 'MetricAlarms[0].ActionsEnabled' --output text)" = "False" ]; then
  echo "==> アラーム ${HEALTH_ALARM} が OK に戻るのを待ってから、通知を戻す（最大6分）"
  for _ in $(seq 1 36); do
    [ "$(aws cloudwatch describe-alarms --region "${AWS_REGION}" --alarm-names "${HEALTH_ALARM}" --query 'MetricAlarms[0].StateValue' --output text)" != "ALARM" ] && break
    sleep 10
  done
  aws cloudwatch enable-alarm-actions --region "${AWS_REGION}" --alarm-names "${HEALTH_ALARM}"
fi
case "${ROLLOUT}" in
  "PRIMARY COMPLETED") ;;
  "PRIMARY IN_PROGRESS")
    echo "入れ替えが5分たっても終わりませんでした（${DEPLOYMENT_ID}）。ECS のコンソールで状態を確かめてください。最近のログ：" >&2
    aws logs tail "${LOG_GROUP}" --region "${AWS_REGION}" --since 15m >&2 || true
    exit 1
    ;;
  *)
    # 新しいタスクが起動に失敗し続けると、前の版に戻して止まる（Terraform の deployment_circuit_breaker）
    echo "入れ替えに失敗し、前の版に戻りました（${DEPLOYMENT_ID}: ${ROLLOUT}）。最近のログ：" >&2
    aws logs tail "${LOG_GROUP}" --region "${AWS_REGION}" --since 15m >&2 || true
    exit 1
    ;;
esac

# --- 動作確認 ---

echo "==> 動作確認（CloudFront → S3・ALB → Fargate）"
check() {
  local label="$1" url="$2" expected="$3" status
  status="$(curl -sS -m 15 -o /dev/null -w '%{http_code}' "${url}" || true)"
  if [ "${status}" = "${expected}" ]; then
    echo "  OK  ${label}: ${status}"
  else
    echo "  NG  ${label}: ${status}（${expected} のはず）" >&2
    FAILED=1
  fi
}
check_unreachable() {
  # ALB は内部用（プライベート IP だけ）で、セキュリティグループも CloudFront の IP からしか受け付けないので、手元からの接続は届かずタイムアウトする。
  # 応答が返ってきたら（403 でも）、CloudFront 以外からつながる状態になっているので失敗にする
  local label="$1" url="$2" status
  status="$(curl -sS -m 5 -o /dev/null -w '%{http_code}' "${url}" 2>/dev/null || true)"
  if [ "${status}" = "000" ]; then
    echo "  OK  ${label}: つながらない"
  else
    echo "  NG  ${label}: ${status}（つながらないはず）" >&2
    FAILED=1
  fi
}
check_private_dns() {
  # ALB は内部用（internal）で、DNS 名はプライベート IP（10.・172.16〜31.・192.168.）だけを返す（CloudFront の VPC オリジン。Issue #161）。
  # インターネットから見た答えを確かめるため、公開の DNS サーバー（1.1.1.1）に問い合わせる（手元の DNS サーバーは dig を断ることがある）
  local label="$1" host="$2" ips
  ips="$(dig +short @1.1.1.1 "${host}" A | grep -E '^[0-9.]+$' || true)"
  if [ -n "${ips}" ] && ! grep -qvE '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)' <<< "${ips}"; then
    echo "  OK  ${label}: $(tr '\n' ' ' <<< "${ips}")"
  else
    echo "  NG  ${label}: $(tr '\n' ' ' <<< "${ips:-（引けない）}")（プライベート IP だけのはず）" >&2
    FAILED=1
  fi
}
check_header() {
  # 応答のヘッダーに、指定した文字列を含む行があるか（CloudFront のレスポンスヘッダーポリシー。security_headers.tf）
  local label="$1" url="$2" expected="$3"
  if curl -sS -m 15 -D - -o /dev/null "${url}" | tr -d '\r' | grep -qiF -- "${expected}"; then
    echo "  OK  ${label}"
  else
    echo "  NG  ${label}: 「${expected}」がない" >&2
    FAILED=1
  fi
}
FAILED=0
check "画面（/）" "${APP_URL}" 200
check "画面の URL を直接開く（/posts/1）" "${APP_URL}posts/1" 200
check "API（/api/health）" "${APP_URL}api/health" 200
check "ログインが必要な API（/api/timeline）" "${APP_URL}api/timeline" 401
check "ない API（/api/no-such-api）は画面にしない" "${APP_URL}api/no-such-api" 401
check "ないファイル（/no-such-file.js）は 404" "${APP_URL}no-such-file.js" 404
check "ない画像（画像用の CloudFront）は 404" "$(tfout image_base_url)/posts/no-such-image.png" 404
# 画像のバケットは CloudFront に一覧の権限（s3:ListBucket）を許可しているので、/ が一覧にならないこと（images.tf の default_root_object）
check "画像用の CloudFront の / はバケットの一覧にしない" "$(tfout image_base_url)/" 404
check_unreachable "ALB に直接アクセス（CloudFront を通らない）" "http://${ALB_DNS}/api/health"
check_private_dns "ALB はインターネットに公開していない" "${ALB_DNS}"
check_header "画面の CSP（ほかのサイトの枠に埋め込ませない）" "${APP_URL}" "content-security-policy: default-src 'self'"
check_header "画面の HSTS" "${APP_URL}" "strict-transport-security: max-age=31536000"
check_header "API の HSTS" "${APP_URL}api/health" "strict-transport-security: max-age=31536000"
ASSET_PATH="$(curl -sS -m 15 "${APP_URL}" | grep -oE '/assets/[^"]+\.js' | head -1 || true)"
check_header "JS・CSS は長くキャッシュする（${ASSET_PATH:-見つからない}）" "${APP_URL}${ASSET_PATH#/}" "cache-control: public, max-age=31536000, immutable"
check_header "index.html は毎回確かめる" "${APP_URL}" "cache-control: no-cache"
check_header "画面の URL（index.html）も毎回確かめる" "${APP_URL}posts/1" "cache-control: no-cache"

if [ "${FAILED}" -ne 0 ]; then
  echo "==> 動作確認で失敗した項目があります" >&2
  exit 1
fi
echo "==> 完了: ${APP_URL} （版: ${APP_VERSION}）"
