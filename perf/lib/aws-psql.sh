# AWS の RDS に psql を流す（docs/performance-test-plan.md「3. 実行する環境」の段階 2）。
# perf/setup-db-aws.sh・perf/db-report.sh（PERF_TARGET=aws）が source して使う。
#
# RDS は VPC の中にあり、手元からは届かない。そこで、一度きりの ECS のタスク（infra/perf.tf の perf-db。PostgreSQL 17 の
# 公式イメージ）を API と同じ場所で起動して psql を動かし、終わるのを待って、出力（CloudWatch Logs）と終了コードを返す。
# DB のパスワードは、タスクの定義で SSM から ECS が渡す（手元には出さない）。
#
#   aws_psql [psql の引数...] [< SQL]
#
# - SQL は、-c で渡すか、標準入力で渡す（-c がないときは標準入力を読む。gzip と base64 にして、コマンドの引数で渡す。
#   ECS のコマンドの上書きは合わせて 8KB まで）
# - 接続先の DB は、タスクの定義の既定で sns_perf（-d postgres で変えられる）

AWS_PSQL_INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../infra" && pwd)"

_aws_psql_tfout() {
  terraform -chdir="${AWS_PSQL_INFRA_DIR}" output -raw "$1"
}

# sh（dash）でそのまま使えるように、1つの値をシングルクォートで囲む
_aws_psql_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

aws_psql() {
  local region cluster task_def network args="" arg sql_b64 script overrides task_arn task_id exit_code stream
  region="$(_aws_psql_tfout aws_region)"
  cluster="$(_aws_psql_tfout ecs_cluster_name)"
  task_def="$(_aws_psql_tfout perf_db_task_definition)"
  network="$(_aws_psql_tfout perf_db_network)"

  local has_c=0
  for arg in "$@"; do
    args+=" $(_aws_psql_quote "${arg}")"
    [ "${arg}" = "-c" ] && has_c=1
  done
  # -c で SQL を渡したときは、標準入力を読まない（標準入力が端末かどうかでは決めない。
  # 端末でない環境から呼ぶと、来ない入力を待ち続けるため）
  if [ "${has_c}" = "1" ]; then
    script="psql -v ON_ERROR_STOP=1${args}"
  else
    sql_b64="$(gzip -9c | base64 | tr -d '\n')"
    script="echo ${sql_b64} | base64 -d | gunzip | psql -v ON_ERROR_STOP=1${args}"
  fi
  if [ "${#script}" -gt 7500 ]; then
    echo "エラー: SQL が大きすぎて、ECS のコマンドの上書き（8KB まで）に入りません（${#script} 文字）" >&2
    return 1
  fi
  overrides="$(python3 -I -c 'import json,sys; print(json.dumps({"containerOverrides":[{"name":"psql","command":[sys.argv[1]]}]}))' "${script}")"

  task_arn="$(aws ecs run-task --region "${region}" --cluster "${cluster}" --task-definition "${task_def}" \
    --launch-type FARGATE --network-configuration "${network}" --overrides "${overrides}" \
    --query 'tasks[0].taskArn' --output text)"
  task_id="${task_arn##*/}"
  aws ecs wait tasks-stopped --region "${region}" --cluster "${cluster}" --tasks "${task_arn}"
  exit_code="$(aws ecs describe-tasks --region "${region}" --cluster "${cluster}" --tasks "${task_arn}" \
    --query 'tasks[0].containers[0].exitCode' --output text)"

  # ログは少し遅れて届くので、出るまで待つ（最大30秒）
  stream="perf-db/psql/${task_id}"
  for _ in $(seq 1 15); do
    if aws logs filter-log-events --region "${region}" --log-group-name "$(_aws_psql_tfout backend_log_group)" \
      --log-stream-names "${stream}" --query 'events[].message' --output text 2> /dev/null | grep -q .; then
      break
    fi
    sleep 2
  done
  aws logs filter-log-events --region "${region}" --log-group-name "$(_aws_psql_tfout backend_log_group)" \
    --log-stream-names "${stream}" --query 'events[].[message]' --output text 2> /dev/null || true

  if [ "${exit_code}" != "0" ]; then
    echo "エラー: psql のタスクが失敗しました（終了コード: ${exit_code}。$(aws ecs describe-tasks --region "${region}" \
      --cluster "${cluster}" --tasks "${task_arn}" --query 'tasks[0].stoppedReason' --output text)）" >&2
    return 1
  fi
}
