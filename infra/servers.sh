#!/bin/bash
# API（ECS Fargate のタスク）を動かす・止める（docs/infrastructure.md「費用」）
# Fargate は動いている時間だけ課金されるので、使わないときはタスクを 0 にする。画面（S3・CloudFront）は止めなくてよい
#   infra/servers.sh start   … タスクを 1 にして、動き出すまで待つ
#   infra/servers.sh stop    … タスクを 0 にする
#   infra/servers.sh status  … タスクの数を表示する
# ALB と RDS は止まらない。ALB は存在するだけで課金されるので、しばらく使わないなら terraform destroy で削除する
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tfout() {
  terraform -chdir="${INFRA_DIR}" output -raw "$1"
}

REGION="$(tfout aws_region)"
CLUSTER="$(tfout ecs_cluster_name)"
SERVICE="$(tfout ecs_service_name)"
# 「API の正常なタスクがない」アラーム（monitoring.tf）。わざと止めている間は鳴らさない
HEALTH_ALARM="$(tfout api_health_alarm_name)"

status() {
  aws ecs describe-services --region "${REGION}" --cluster "${CLUSTER}" --services "${SERVICE}" \
    --query 'services[0].{desired:desiredCount,running:runningCount,pending:pendingCount}' --output table
}

scale() {
  aws ecs update-service --region "${REGION}" --cluster "${CLUSTER}" --service "${SERVICE}" --desired-count "$1" > /dev/null
}

case "${1:-}" in
  start)
    scale 1
    echo "==> タスクが動き出し、ALB のヘルスチェックに通るのを待っています（2〜3分）"
    aws ecs wait services-stable --region "${REGION}" --cluster "${CLUSTER}" --services "${SERVICE}"
    aws cloudwatch enable-alarm-actions --region "${REGION}" --alarm-names "${HEALTH_ALARM}"
    echo "==> アラーム ${HEALTH_ALARM} の通知を戻しました"
    status
    echo "==> アプリのURL: $(tfout app_url)"
    ;;
  stop)
    # 止めると正常なタスクが 0 になり、アラームが鳴ってしまうので、先に通知を止める（start・deploy.sh で戻す）
    aws cloudwatch disable-alarm-actions --region "${REGION}" --alarm-names "${HEALTH_ALARM}"
    echo "==> アラーム ${HEALTH_ALARM} の通知を止めました"
    scale 0
    echo "==> タスクの停止を待っています"
    aws ecs wait services-stable --region "${REGION}" --cluster "${CLUSTER}" --services "${SERVICE}"
    status
    echo "==> ALB と RDS は動いたままで、課金が続きます。しばらく使わないなら terraform -chdir=infra destroy で削除してください"
    ;;
  status)
    status
    ;;
  *)
    echo "使い方: $0 start | stop | status" >&2
    exit 1
    ;;
esac
