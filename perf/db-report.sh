#!/usr/bin/env bash
#
# パフォーマンステストの DB（sns_perf）の、SQL の速さを見る（docs/performance-test-plan.md「7. 見る値と記録」）。
#
# 使い方（リポジトリのルートで）:
#   perf/db-report.sh reset         pg_stat_statements の集計を空にする（シナリオを流す直前に実行する）
#   perf/db-report.sh top [件数]    合計時間の長い SQL を順に出す（既定は 10 件。シナリオを流したあとに実行する）
#   perf/db-report.sh explain [ID]  「5. 重点的に確かめる箇所」の EXPLAIN (ANALYZE, BUFFERS)（ID はログイン中のユーザー。既定は 1）

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

psql_perf() {
  docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T postgres \
    sh -c 'psql -U "$POSTGRES_USER" -d sns_perf -v ON_ERROR_STOP=1 "$@"' psql "$@"
}

case "${1:-}" in
  reset)
    psql_perf -q -c "SELECT pg_stat_statements_reset()" >/dev/null
    echo "pg_stat_statements の集計を空にした"
    ;;
  top)
    limit="${2:-10}"
    # 改行と連続する空白を詰めて、SQL を1行で読めるようにする
    psql_perf -P pager=off -c "
      SELECT calls AS \"回数\",
             round(total_exec_time::numeric, 1) AS \"合計(ms)\",
             round(mean_exec_time::numeric, 2) AS \"平均(ms)\",
             round(max_exec_time::numeric, 1) AS \"最大(ms)\",
             rows AS \"行数\",
             left(regexp_replace(query, '\s+', ' ', 'g'), 140) AS \"SQL\"
        FROM pg_stat_statements
       WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'sns_perf')
         AND query NOT ILIKE '%pg_stat_statements%'
       ORDER BY total_exec_time DESC
       LIMIT $limit"
    ;;
  explain)
    psql_perf -v me="${2:-1}" < "$ROOT_DIR/perf/data/explain.sql"
    ;;
  *)
    sed -n '5,8p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 1
    ;;
esac
