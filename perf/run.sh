#!/usr/bin/env bash
#
# k6（Docker）でパフォーマンステストのシナリオを動かす（docs/performance-test-plan.md「8. 動かし方」）。
#
# 使い方（リポジトリのルートで）:
#   perf/run.sh <シナリオ> [接続先の URL]
#
#   シナリオ  perf/scenarios/<シナリオ>.js（smoke・load）
#   接続先    既定は http://host.docker.internal:8080（k6 のコンテナから見た、Mac で動いているバックエンド）。
#             AWS では CloudFront の URL（https://xxxx.cloudfront.net）を渡す
#
# 環境変数（そのまま k6 に渡す）:
#   USERS     テストデータのユーザー数（既定 500。perf/setup-db.sh x10 のときは 5000）
#   VUS       同時の人数（load。既定 50）
#   DURATION  人数がそろってから続ける時間（load。既定 10m）
#
# 例:
#   perf/run.sh smoke
#   VUS=10 DURATION=1m perf/run.sh load
#
# 合格の基準（thresholds）を満たさなかったときは、k6 が 0 以外の終了コードで終わる。
# 結果の要約（JSON）は perf/results/<シナリオ>-<日時>.json に残す（Git には入れない）。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# 版は導入したときの最新の安定版に固定する（docs/tech-stack.md の決め方）
K6_IMAGE="grafana/k6:2.3.0"

scenario="${1:-}"
base_url="${2:-http://host.docker.internal:8080}"

if [ -z "$scenario" ] || [ ! -f "$ROOT_DIR/perf/scenarios/$scenario.js" ]; then
  sed -n '5,10p' "$0" | sed 's/^# \{0,1\}//' >&2
  echo "シナリオ: $(cd "$ROOT_DIR/perf/scenarios" && ls -- *.js | sed 's/\.js$//' | tr '\n' ' ')" >&2
  exit 1
fi

mkdir -p "$ROOT_DIR/perf/results"
result="${scenario}-$(date +%Y%m%d-%H%M%S).json"

env_args=(-e "BASE_URL=${base_url}")
for name in USERS VUS DURATION; do
  if [ -n "${!name:-}" ]; then
    env_args+=(-e "${name}=${!name}")
  fi
done

echo "== ${scenario} を ${base_url} に流す（結果: perf/results/${result}）"
# 結果のファイルが root の持ち物にならないよう、Mac のユーザーで動かす
docker run --rm -i \
  --user "$(id -u):$(id -g)" \
  -v "$ROOT_DIR/perf/scenarios:/scripts:ro" \
  -v "$ROOT_DIR/perf/results:/results" \
  "$K6_IMAGE" run \
  "${env_args[@]}" \
  --summary-trend-stats "avg,med,p(95),p(99),max" \
  --summary-export "/results/${result}" \
  "/scripts/${scenario}.js"
