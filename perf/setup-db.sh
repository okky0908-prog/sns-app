#!/usr/bin/env bash
#
# パフォーマンステスト専用の DB（sns_perf）を作り、テストデータを入れる（docs/performance-test-plan.md「4. テストデータ」）。
#
# - docker compose の PostgreSQL の中に sns_perf を作る（開発用の sns には触らない）
# - テーブルは Flyway（Docker イメージ）で作る。バックエンドと同じマイグレーションのファイルを使う
# - データは perf/data/seed.sql で入れる。前のデータは消してから入れ直すので、何度実行してもよい
#
# 使い方（リポジトリのルートで。docker compose の postgres を起動しておく）:
#   perf/setup-db.sh base   基準の規模（ユーザー 500・投稿 5,000）
#   perf/setup-db.sh x10    10倍の規模（ユーザー 5,000・投稿 5万）
#
# ログインは perf00001@example.com 〜（パスワードは password123）。
# バックエンドを sns_perf につなぐには、環境変数 POSTGRES_DB=sns_perf を付けて起動する（.env より環境変数が優先される）。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DB_NAME="sns_perf"
# バックエンドの Flyway（spring-boot-starter-flyway が使う flyway-core）と同じ版にする。
# Spring Boot を上げて Flyway の版が変わったら、ここも合わせる（./gradlew dependencies で確かめる）
FLYWAY_IMAGE="flyway/flyway:12.4.0"

usage() {
  sed -n '9,11p' "$0" | sed 's/^# //' >&2
  exit 1
}

# 規模ごとの値（docs/performance-test-plan.md「4. テストデータ」の表）。
# 基準の規模はユーザーが 500 人しかいないので、偏りのあるデータの人数は「自分以外の全員」までにしている
case "${1:-}" in
  base)
    SEED_VARS=(
      -v users=500 -v posts=5000 -v follows_per_user=50 -v likes=50000 -v comments=10000
      -v heavy_follows=499 -v star_followers=499 -v hot_likes=500 -v hot_comments=500
    )
    ;;
  x10)
    SEED_VARS=(
      -v users=5000 -v posts=50000 -v follows_per_user=100 -v likes=500000 -v comments=100000
      -v heavy_follows=500 -v star_followers=1000 -v hot_likes=1000 -v hot_comments=500
    )
    ;;
  *)
    usage
    ;;
esac

compose() {
  docker compose -f "$ROOT_DIR/docker-compose.yml" "$@"
}

container_id="$(compose ps -q postgres)"
if [ -z "$container_id" ]; then
  echo "エラー: docker compose の postgres が起動していません（docker compose up -d postgres）" >&2
  exit 1
fi

# 接続のユーザー名・パスワードは、コンテナに渡した値（.env の POSTGRES_USER・POSTGRES_PASSWORD）をそのまま使う
pg_user="$(compose exec -T postgres printenv POSTGRES_USER)"
pg_password="$(compose exec -T postgres printenv POSTGRES_PASSWORD)"

psql_in() {
  compose exec -T postgres psql -U "$pg_user" -v ON_ERROR_STOP=1 "$@"
}

echo "== 1. DB（${DB_NAME}）を用意する"
if [ "$(psql_in -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'")" = "1" ]; then
  echo "すでにあるので、そのまま使う"
else
  psql_in -d postgres -c "CREATE DATABASE $DB_NAME"
fi
# SQL ごとの実行時間を集計する拡張（perf/db-report.sh top で見る）。docker-compose.yml で読み込んである前提
psql_in -d "$DB_NAME" -q -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"

echo "== 2. テーブルを作る（Flyway）"
# Flyway のコンテナを postgres と同じネットワークにつなぎ、サービス名（postgres）で接続する
network="$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{end}}' "$container_id")"
docker run --rm \
  --network "$network" \
  -v "$ROOT_DIR/backend/src/main/resources/db/migration:/migrations:ro" \
  -e FLYWAY_LOCATIONS="filesystem:/migrations" \
  -e FLYWAY_URL="jdbc:postgresql://postgres:5432/$DB_NAME" \
  -e FLYWAY_USER="$pg_user" \
  -e FLYWAY_PASSWORD="$pg_password" \
  "$FLYWAY_IMAGE" -q migrate

echo "== 3. データを入れる（${1}）"
psql_in -d "$DB_NAME" "${SEED_VARS[@]}" < "$ROOT_DIR/perf/data/seed.sql"

echo "完了。バックエンドは POSTGRES_DB=${DB_NAME} を付けて起動する"
