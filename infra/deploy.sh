#!/bin/bash
# ローカル（Mac）でバックエンド・フロントエンドをビルドし、AWS の EC2 にデプロイする（docs/infrastructure.md「デプロイ・運用の考え方」）
# Terraform の一部ではない。`terraform apply` で環境を作り、EC2 が起動していることが前提
# EC2 はメモリが少ないため、ビルドはすべてローカルで行い、成果物だけを配置する
#
# 使い方: infra/deploy.sh
# 必要なもの: terraform・aws（認証済み）・Java 25（./gradlew が使う）・Node.js（frontend/.nvmrc の版）・SSH の秘密鍵
#   SSH の秘密鍵は既定で ~/.ssh/sns-app-aws-ec2。違う場所なら SSH_KEY=... infra/deploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
INFRA_DIR="${SCRIPT_DIR}"
SSH_KEY="${SSH_KEY:-${HOME}/.ssh/sns-app-aws-ec2}"

# terraform.tfvars から値を読む（例：db_password = "..."）
tfvar() {
  grep "^$1[[:space:]]*=" "${INFRA_DIR}/terraform.tfvars" | sed -E 's/^[^=]+=[[:space:]]*"(.*)"[[:space:]]*(#.*)?$/\1/'
}
tfout() {
  terraform -chdir="${INFRA_DIR}" output -raw "$1"
}

echo "==> Terraform の出力値を取得"
PROJECT="$(tfout project_name)"
APP_URL="$(tfout app_url)"
ALB_DNS="$(tfout alb_dns_name)"
FRONTEND_IP="$(tfout frontend_public_ip)"
BACKEND_IP="$(tfout backend_public_ip)"
RDS_ENDPOINT="$(tfout rds_endpoint)"
DB_NAME="$(tfout db_name)"
DB_USERNAME="$(tfout db_username)"
S3_BUCKET="$(tfout s3_bucket)"
AWS_REGION="$(tfout aws_region)"
IMAGE_BASE_URL="$(tfout image_base_url)"
DB_HOST="${RDS_ENDPOINT%%:*}"
DB_PORT="${RDS_ENDPOINT##*:}"
DB_PASSWORD="$(tfvar db_password)"
# ログに入れるリリースの版（docs/observability.md）。コミットしていない変更があれば印を付ける
APP_VERSION="$(git -C "${REPO_ROOT}" rev-parse --short HEAD)$(git -C "${REPO_ROOT}" diff --quiet HEAD || echo '-dirty')"
REMOTE_DIR="/opt/${PROJECT}"

if [ -z "${FRONTEND_IP}" ] || [ -z "${BACKEND_IP}" ]; then
  echo "EC2 の公開IPが取得できませんでした。EC2 が起動しているか確認してください（infra/servers.sh start）。" >&2
  exit 1
fi
if [ -z "${DB_PASSWORD}" ]; then
  echo "terraform.tfvars の db_password が読み取れませんでした。" >&2
  exit 1
fi

SSH_OPTS=(-i "${SSH_KEY}" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10)
ssh_frontend() { ssh "${SSH_OPTS[@]}" "ec2-user@${FRONTEND_IP}" "$@"; }
ssh_backend() { ssh "${SSH_OPTS[@]}" "ec2-user@${BACKEND_IP}" "$@"; }

echo "==> EC2 の初期設定（user_data）が終わるのを待つ"
for host in "${FRONTEND_IP}" "${BACKEND_IP}"; do
  ssh "${SSH_OPTS[@]}" "ec2-user@${host}" "cloud-init status --wait > /dev/null" || {
    echo "${host} の初期設定が失敗しました。サーバーの /var/log/cloud-init-output.log を確認してください。" >&2
    exit 1
  }
done

# --- バックエンド ---

echo "==> バックエンドをビルド（./gradlew bootJar）"
(cd "${REPO_ROOT}/backend" && ./gradlew --quiet clean bootJar)
JAR="$(find "${REPO_ROOT}/backend/build/libs" -name '*.jar' ! -name '*-plain.jar' | head -n 1)"
if [ -z "${JAR}" ]; then
  echo "jar が見つかりませんでした（backend/build/libs）。" >&2
  exit 1
fi

echo "==> jar を EC2 へ転送"
scp "${SSH_OPTS[@]}" "${JAR}" "ec2-user@${BACKEND_IP}:/tmp/backend.jar"
ssh_backend "sudo install -m 644 -o root -g root /tmp/backend.jar ${REMOTE_DIR}/backend.jar && rm -f /tmp/backend.jar"

echo "==> 設定（backend.env）を EC2 へ配置"
# JWT の署名鍵は、初回だけ EC2 上で作り、2回目以降は同じ値を使い続ける（変えると、ログイン中の人が全員ログアウトになる）。
# 鍵はサーバーの外に出さない
ssh_backend "sudo bash -c 'umask 077 && test -f ${REMOTE_DIR}/jwt_secret || openssl rand -base64 48 | tr -d \"\\n\" > ${REMOTE_DIR}/jwt_secret'"
ssh_backend "sudo bash -c 'umask 077 && { cat; echo JWT_SECRET=\$(cat ${REMOTE_DIR}/jwt_secret); } > ${REMOTE_DIR}/backend.env'" << EOF
DB_HOST=${DB_HOST}
POSTGRES_PORT=${DB_PORT}
POSTGRES_DB=${DB_NAME}
POSTGRES_USER=${DB_USERNAME}
POSTGRES_PASSWORD=${DB_PASSWORD}
REFRESH_TOKEN_COOKIE_SECURE=true
S3_BUCKET=${S3_BUCKET}
AWS_REGION=${AWS_REGION}
S3_ENDPOINT=
IMAGE_BASE_URL=${IMAGE_BASE_URL}
APP_VERSION=${APP_VERSION}
EOF

echo "==> バックエンドを再起動（起動のたびに Flyway がテーブルを作成・更新する）"
ssh_backend "sudo systemctl restart ${PROJECT}-backend"

echo "==> バックエンドの起動を待つ（最大3分）"
for _ in $(seq 1 90); do
  if ssh_backend "curl -sf -o /dev/null http://localhost:8080/api/health"; then
    break
  fi
  sleep 2
done
ssh_backend "curl -sf -o /dev/null http://localhost:8080/api/health" || {
  echo "バックエンドが起動しませんでした。ログ：" >&2
  ssh_backend "sudo journalctl -u ${PROJECT}-backend --no-pager -n 50" >&2
  exit 1
}

# --- フロントエンド ---

echo "==> フロントエンドをビルド（API は同じドメインの /api を呼ぶので、URL を埋め込む必要はない）"
(cd "${REPO_ROOT}/frontend" && npm ci --silent && npm run build --silent)

echo "==> フロントエンドを EC2 へ配置"
tar -C "${REPO_ROOT}/frontend/dist" -czf - . | ssh_frontend "rm -rf /usr/share/nginx/${PROJECT}/* && tar -C /usr/share/nginx/${PROJECT} -xzf -"

# --- 動作確認 ---

echo "==> 動作確認（CloudFront → ALB → EC2）"
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
FAILED=0
# ALB のヘルスチェックが通るまで（ターゲットが healthy になるまで）は 502・503 になるので、少し待つ
for _ in $(seq 1 30); do
  [ "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "${APP_URL}api/health")" = "200" ] && break
  sleep 5
done
check "画面（/）" "${APP_URL}" 200
check "画面の URL を直接開く（/posts/1）" "${APP_URL}posts/1" 200
check "API（/api/health）" "${APP_URL}api/health" 200
check "ログインが必要な API（/api/timeline）" "${APP_URL}api/timeline" 401
check "ALB に直接アクセス（CloudFront を通らない）" "http://${ALB_DNS}/" 403

if [ "${FAILED}" -ne 0 ]; then
  echo "==> 動作確認で失敗した項目があります" >&2
  exit 1
fi
echo "==> 完了: ${APP_URL} （版: ${APP_VERSION}）"
