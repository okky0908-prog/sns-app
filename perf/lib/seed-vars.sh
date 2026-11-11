# テストデータの規模ごとの値（docs/performance-test-plan.md「4. テストデータ」の表）。
# perf/setup-db.sh（ローカル）と perf/setup-db-aws.sh（AWS）が source して使う。
#
# seed_vars <base|x10> で、seed.sql に渡す psql の変数（-v 名前=値）を、配列 SEED_VARS に入れる。
# 基準の規模はユーザーが 500 人しかいないので、偏りのあるデータの人数は「自分以外の全員」までにしている
seed_vars() {
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
      return 1
      ;;
  esac
}
