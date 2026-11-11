#!/usr/bin/env bash
#
# AWS の RDS に、パフォーマンステスト専用の DB（sns_perf）を作り、テストデータを入れる
# （docs/performance-test-plan.md「3. 実行する環境」の段階 2。ローカルは perf/setup-db.sh）。
#
# 使い方（リポジトリのルートで。terraform apply のあと）:
#   perf/setup-db-aws.sh create        sns_perf と pg_stat_statements を作る（API を perf_test = true でつなぎ替える前に1回）
#   perf/setup-db-aws.sh seed base     データを入れる（基準の規模。10倍は x10）。テーブルは API の起動のとき Flyway が作るので、
#                                      terraform apply -var perf_test=true で API を sns_perf につないでから実行する
#
# 開発用の DB（ローカル）にも本番の DB（sns）にも触らない。seed.sql は sns_perf 以外では止まる。
# ログインは perf00001@example.com 〜（パスワードは password123）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/aws-psql.sh
source "$ROOT_DIR/perf/lib/aws-psql.sh"
# shellcheck source=lib/seed-vars.sh
source "$ROOT_DIR/perf/lib/seed-vars.sh"

usage() {
  sed -n '7,10p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 1
}

case "${1:-}" in
  create)
    echo "== sns_perf を作る（すでにあればそのまま使う）"
    aws_psql -d postgres -q <<'SQL'
SELECT 'CREATE DATABASE sns_perf' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'sns_perf')\gexec
SQL
    echo "== pg_stat_statements（SQL ごとの実行時間の集計）を作る"
    aws_psql -q <<'SQL'
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SELECT extname, extversion FROM pg_extension WHERE extname = 'pg_stat_statements';
SQL
    echo "完了。次に terraform -chdir=infra apply -var perf_test=true で API を sns_perf につなぎ替え、seed を実行する"
    ;;
  seed)
    seed_vars "${2:-}" || usage
    echo "== データを入れる（${2}。数分かかる）"
    aws_psql "${SEED_VARS[@]}" < "$ROOT_DIR/perf/data/seed.sql"
    echo "完了"
    ;;
  *)
    usage
    ;;
esac
