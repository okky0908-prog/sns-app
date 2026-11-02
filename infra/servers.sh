#!/bin/bash
# EC2（フロントエンド・バックエンドの2台）を起動・停止する（docs/infrastructure.md）
# EC2 の無料利用枠は2台合計で月750時間のため、使わないときは停止する。RDS は動かしたまま（無料利用枠の範囲）
#   infra/servers.sh start   … 2台を起動し、アプリのURLを表示する（SSH 用の公開IPは起動のたびに変わる）
#   infra/servers.sh stop    … 2台を停止する
#   infra/servers.sh status  … 2台の状態を表示する
# ALB と CloudFront は止められない。ALB は存在するだけで課金されるので、しばらく使わないなら terraform destroy で削除する
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tfout() {
  terraform -chdir="${INFRA_DIR}" output -raw "$1"
}

REGION="$(tfout aws_region)"
FRONTEND_ID="$(tfout frontend_instance_id)"
BACKEND_ID="$(tfout backend_instance_id)"

status() {
  aws ec2 describe-instances --region "${REGION}" --instance-ids "${FRONTEND_ID}" "${BACKEND_ID}" \
    --query 'Reservations[].Instances[].[Tags[?Key==`Name`]|[0].Value,State.Name,PublicIpAddress]' \
    --output table
}

case "${1:-}" in
  start)
    aws ec2 start-instances --region "${REGION}" --instance-ids "${BACKEND_ID}" "${FRONTEND_ID}" > /dev/null
    echo "==> 起動を待っています"
    aws ec2 wait instance-running --region "${REGION}" --instance-ids "${BACKEND_ID}" "${FRONTEND_ID}"
    # 公開IP（SSH 用）が変わるため、Terraform の出力値を最新にする（リソースは変更しない）
    terraform -chdir="${INFRA_DIR}" apply -refresh-only -auto-approve > /dev/null
    status
    echo "==> アプリのURL: $(tfout app_url)"
    echo "    （起動してから、ALB のヘルスチェックが通ってアプリが使えるようになるまで、2分ほどかかります）"
    ;;
  stop)
    aws ec2 stop-instances --region "${REGION}" --instance-ids "${FRONTEND_ID}" "${BACKEND_ID}" > /dev/null
    echo "==> 停止を待っています"
    aws ec2 wait instance-stopped --region "${REGION}" --instance-ids "${FRONTEND_ID}" "${BACKEND_ID}"
    status
    echo "==> ALB は止められず、課金が続きます。しばらく使わないなら terraform -chdir=infra destroy で削除してください"
    ;;
  status)
    status
    ;;
  *)
    echo "使い方: $0 start | stop | status" >&2
    exit 1
    ;;
esac
