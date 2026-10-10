#!/usr/bin/env bash
#
# 動作確認用のテストデータを入れる（ローカル開発専用）。
#
# - ユーザーと投稿は API 経由で作る（パスワードのハッシュ化・本文の前後空白の除去などを、画面から操作したときと同じ処理にするため）
# - フォローは API がまだないので、DB（docker compose の postgres）に直接登録する
#
# テスト用ユーザーは seed_alice / seed_bob / seed_carol（パスワードはすべて password123、
# メールアドレスは <ユーザー名>@example.com）。すでにあればログインして使う。
#
# 使い方（リポジトリのルートで。バックエンドと DB を起動しておく）:
#   scripts/seed.sh users                         テスト用ユーザーを作る
#   scripts/seed.sh posts [件数] [ユーザー名]     投稿する（既定は 65 件。ユーザー名を省くと3人が順番に投稿する）
#   scripts/seed.sh follow <ユーザー名> <ユーザー名>...
#                                                 1人目が2人目以降をフォローする（seed_ 以外の自分のユーザーも指定できる）
#   scripts/seed.sh likes <投稿ID> [人数]         テスト用ユーザーがいいねする（既定は3人全員。1〜3）
#   scripts/seed.sh comments <投稿ID> [件数]      テスト用ユーザーが順番にコメントする（既定は 25 件）
#   scripts/seed.sh fans <ユーザー名> [人数]       フォロワー用のユーザー（seed_fan1〜）を作り、その人をフォローさせる（既定は 25 人）
#
# 例:
#   scripts/seed.sh posts 65                  # 無限スクロール（20件ずつ）を4ページ分確認する
#   scripts/seed.sh follow myname seed_alice  # 自分の「フォロー中」タブに seed_alice の投稿が出るようにする
#   scripts/seed.sh posts 3 seed_alice        # 画面を開いたまま実行し、「↑ 3件の新しい投稿」を確認する
#   scripts/seed.sh posts 101                 # 「99+件の新しい投稿」を確認する
#   scripts/seed.sh likes 123 2               # 投稿 123 に2人がいいねする（ほかの人のいいねが数に入るかを確認する）
#   scripts/seed.sh comments 123 25           # 投稿 123 に25件コメントする（20件ずつの「さらに表示」を確認する）
#   scripts/seed.sh fans seed_alice 25        # seed_alice のフォロワーを25人にする（一覧の無限スクロールを確認する）
#
# 接続先は環境変数 API_BASE で変えられる（既定は http://localhost:8080）。

set -euo pipefail

API_BASE="${API_BASE:-http://localhost:8080}"
PASSWORD="password123"
SEED_USERS=(seed_alice seed_bob seed_carol)
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

die() {
  echo "エラー: $*" >&2
  exit 1
}

# API を呼ぶ。2xx 以外ならエラーレスポンス（code・message）を表示して止める
# 使い方: api <メソッド> <パス> <JSON> [アクセストークン]
api() {
  local method=$1 path=$2 body=$3 token=${4:-}
  local args=(-sS -X "$method" -H 'Content-Type: application/json' -d "$body" -w '\n%{http_code}')
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  local response status
  response=$(curl "${args[@]}" "$API_BASE$path") || die "$API_BASE に接続できません。バックエンドを起動してください"
  status=${response##*$'\n'}
  response=${response%$'\n'*}
  if [[ $status != 2* ]]; then
    echo "$response" >&2
    return 1
  fi
  echo "$response"
}

# 投稿する（A-11 は multipart/form-data）。2xx 以外ならエラーレスポンスを表示して失敗を返す
# 使い方: post_multipart <アクセストークン> <本文>
post_multipart() {
  local token=$1 content=$2 response status
  response=$(curl -sS -X POST -H "Authorization: Bearer $token" \
    --form-string "content=$content" -w '\n%{http_code}' "$API_BASE/api/posts") ||
    die "$API_BASE に接続できません。バックエンドを起動してください"
  status=${response##*$'\n'}
  if [[ $status != 2* ]]; then
    echo "${response%$'\n'*}" >&2
    return 1
  fi
}

# ユーザーのアクセストークンを返す。なければ新規登録する
token_of() {
  local username=$1 email="$1@example.com" response
  if response=$(api POST /api/auth/login "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}" 2>/dev/null); then
    jq -r .accessToken <<<"$response"
    return
  fi
  response=$(api POST /api/auth/signup \
    "{\"username\":\"$username\",\"displayName\":\"${username}（テスト）\",\"email\":\"$email\",\"password\":\"$PASSWORD\"}") ||
    die "$username を登録できませんでした（上のエラーを確認してください）"
  echo "ユーザーを作成しました: $username" >&2
  jq -r .accessToken <<<"$response"
}

cmd_users() {
  for username in "${SEED_USERS[@]}"; do
    token_of "$username" >/dev/null
  done
  echo "テスト用ユーザー: ${SEED_USERS[*]}（パスワード: ${PASSWORD}、メールアドレス: <ユーザー名>@example.com）"
}

cmd_posts() {
  local count=${1:-65} only_user=${2:-}
  [[ $count =~ ^[0-9]+$ ]] || die "件数は数字で指定してください: $count"
  local users=("${SEED_USERS[@]}")
  [[ -n $only_user ]] && users=("$only_user")

  # ユーザーごとのトークンを先に取る（ログインは BCrypt の照合で遅いので、投稿のたびにはしない）
  local tokens=() username
  for username in "${users[@]}"; do
    tokens+=("$(token_of "$username")")
  done

  local stamp i index
  stamp=$(date '+%H:%M:%S')
  for ((i = 1; i <= count; i++)); do
    index=$(((i - 1) % ${#users[@]}))
    post_multipart "${tokens[$index]}" "テスト投稿 ${i}/${count}（${users[$index]}、${stamp} に投入）" ||
      die "${i} 件目の投稿に失敗しました"
    ((i % 20 == 0)) && echo "  $i / $count 件"
  done
  echo "$count 件投稿しました（${users[*]}）"
}

cmd_follow() {
  (($# >= 2)) || die "使い方: scripts/seed.sh follow <フォローする人> <フォローされる人>..."
  local follower=$1
  shift
  local followee sql=""
  for followee in "$@"; do
    [[ $follower =~ ^[A-Za-z0-9_]+$ && $followee =~ ^[A-Za-z0-9_]+$ ]] || die "ユーザー名の形式が正しくありません"
    [[ $follower != "$followee" ]] || die "自分自身はフォローできません"
    sql+="INSERT INTO follows (follower_id, followee_id, created_at)
          SELECT a.id, b.id, now() FROM users a, users b
          WHERE lower(a.username) = lower('$follower') AND lower(b.username) = lower('$followee')
          ON CONFLICT DO NOTHING;"
  done
  # .env の POSTGRES_USER・POSTGRES_DB を使う（docker compose が読むのと同じ値）
  local db_user db_name
  db_user=$(grep -E '^POSTGRES_USER=' "$ROOT_DIR/.env" 2>/dev/null | cut -d= -f2 || true)
  db_name=$(grep -E '^POSTGRES_DB=' "$ROOT_DIR/.env" 2>/dev/null | cut -d= -f2 || true)
  (cd "$ROOT_DIR" && docker compose exec -T postgres \
    psql -v ON_ERROR_STOP=1 -q -U "${db_user:-sns}" -d "${db_name:-sns}" -c "$sql") ||
    die "DB に登録できませんでした（docker compose で postgres が起動しているか確認してください）"
  echo "$follower が $* をフォローしました（存在しないユーザー名は無視されます）"
}

cmd_likes() {
  local post_id=${1:-} count=${2:-${#SEED_USERS[@]}}
  [[ $post_id =~ ^[0-9]+$ ]] || die "使い方: scripts/seed.sh likes <投稿ID> [人数]"
  [[ $count =~ ^[1-3]$ ]] || die "人数は1〜3で指定してください: $count"
  local i response
  for ((i = 0; i < count; i++)); do
    response=$(api POST "/api/posts/${post_id}/likes" '{}' "$(token_of "${SEED_USERS[$i]}")") ||
      die "${SEED_USERS[$i]} のいいねに失敗しました（上のエラーを確認してください）"
  done
  echo "投稿 ${post_id} に ${count} 人がいいねしました（いいね数: $(jq -r .likeCount <<<"$response")）"
}

cmd_comments() {
  local post_id=${1:-} count=${2:-25}
  [[ $post_id =~ ^[0-9]+$ ]] || die "使い方: scripts/seed.sh comments <投稿ID> [件数]"
  [[ $count =~ ^[0-9]+$ ]] || die "件数は数字で指定してください: $count"
  local tokens=() username
  for username in "${SEED_USERS[@]}"; do
    tokens+=("$(token_of "$username")")
  done
  local i index response stamp
  stamp=$(date '+%H:%M:%S')
  for ((i = 1; i <= count; i++)); do
    index=$(((i - 1) % ${#SEED_USERS[@]}))
    response=$(api POST "/api/posts/${post_id}/comments" \
      "$(jq -n --arg c "テストコメント ${i}/${count}（${SEED_USERS[$index]}、${stamp} に投入）" '{content: $c}')" \
      "${tokens[$index]}") || die "${i} 件目のコメントに失敗しました（上のエラーを確認してください）"
  done
  echo "投稿 ${post_id} に ${count} 件コメントしました（コメント数: $(jq -r .commentCount <<<"$response")）"
}

cmd_fans() {
  local target=${1:-} count=${2:-25}
  [[ $target =~ ^[A-Za-z0-9_]+$ ]] || die "使い方: scripts/seed.sh fans <ユーザー名> [人数]"
  [[ $count =~ ^[0-9]+$ ]] || die "人数は数字で指定してください: $count"
  local i response
  for ((i = 1; i <= count; i++)); do
    response=$(api POST "/api/users/${target}/follow" '{}' "$(token_of "seed_fan${i}")") ||
      die "seed_fan${i} のフォローに失敗しました（上のエラーを確認してください）"
  done
  echo "${target} を ${count} 人がフォローしました（フォロワー数: $(jq -r .followerCount <<<"$response")）"
}

command -v jq >/dev/null || die "jq が必要です（brew install jq）"

case ${1:-} in
  users) cmd_users ;;
  posts) shift && cmd_posts "$@" ;;
  follow) shift && cmd_follow "$@" ;;
  likes) shift && cmd_likes "$@" ;;
  comments) shift && cmd_comments "$@" ;;
  fans) shift && cmd_fans "$@" ;;
  *)
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
