-- 「5. 重点的に確かめる箇所」の SQL の実行計画（docs/performance-test-plan.md）。perf/db-report.sh explain から実行する。
-- Mapper（backend/src/main/resources/mapper/*.xml）の SQL を、値を埋めて書き写したもの。Mapper を変えたらここも合わせる。
--
-- 変数: me  ログイン中のユーザーの ID（1 はたくさんフォローしている perf00001）

\set ON_ERROR_STOP on
\pset pager off

\echo '==== フォロー中のタイムライン（PostMapper.findFollowingTimeline、1ページ目）'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, p.user_id, p.content, p.created_at, p.updated_at, p.edited_at,
       u.username, u.display_name, u.icon_key,
       (SELECT count(*) FROM likes l WHERE l.post_id = p.id) AS like_count,
       EXISTS (SELECT 1 FROM likes l WHERE l.post_id = p.id AND l.user_id = :me) AS liked_by_me,
       (SELECT count(*) FROM comments c WHERE c.post_id = p.id) AS comment_count
FROM posts p
JOIN users u ON u.id = p.user_id
WHERE (p.user_id = :me
   OR p.user_id IN (SELECT f.followee_id FROM follows f WHERE f.follower_id = :me))
ORDER BY p.created_at DESC, p.id DESC
LIMIT 21;

\echo '==== 全体のタイムライン（PostMapper.findAll、1ページ目。いいねの多い投稿 1 を含む）'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, p.user_id, p.content, p.created_at, p.updated_at, p.edited_at,
       u.username, u.display_name, u.icon_key,
       (SELECT count(*) FROM likes l WHERE l.post_id = p.id) AS like_count,
       EXISTS (SELECT 1 FROM likes l WHERE l.post_id = p.id AND l.user_id = :me) AS liked_by_me,
       (SELECT count(*) FROM comments c WHERE c.post_id = p.id) AS comment_count
FROM posts p
JOIN users u ON u.id = p.user_id
WHERE TRUE
ORDER BY p.created_at DESC, p.id DESC
LIMIT 21;

\echo '==== 新しい投稿の件数：フォロー中（PostMapper.countNewInFollowingTimeline。since は全体の半分の ID）'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM (
  SELECT 1 FROM posts p
  WHERE p.user_id IN (SELECT f.followee_id FROM follows f WHERE f.follower_id = :me)
    AND p.id > (SELECT max(id) / 2 FROM posts)
  LIMIT 100
) AS new_posts;

\echo '==== 新しい投稿の件数：フォロー中（since が一番新しい投稿＝ふだんの呼ばれ方。0 件）'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM (
  SELECT 1 FROM posts p
  WHERE p.user_id IN (SELECT f.followee_id FROM follows f WHERE f.follower_id = :me)
    AND p.id > (SELECT max(id) FROM posts)
  LIMIT 100
) AS new_posts;

\echo '==== 新しい投稿の件数：全体（PostMapper.countNewInAll。since が一番新しい投稿）'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM (
  SELECT 1 FROM posts p
  WHERE p.user_id <> :me
    AND p.id > (SELECT max(id) FROM posts)
  LIMIT 100
) AS new_posts;

\echo '==== ユーザー検索（UserMapper.search、キーワード「性能」。2 文字で、全員が当てはまる＝索引で絞れない場合。followedByMe は省略）'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM (
  SELECT u.id, u.username, u.display_name,
         CASE
           WHEN LOWER(u.username) = LOWER('性能') THEN 0
           WHEN u.username ILIKE '性能' || '%' THEN 1
           ELSE 2
         END AS match_rank
  FROM users u
  WHERE u.username ILIKE '%' || '性能' || '%'
     OR u.display_name ILIKE '%' || '性能' || '%'
) found
ORDER BY found.match_rank, LOWER(found.username)
LIMIT 21;

\echo '==== ユーザー検索（UserMapper.search、キーワード「perf0042」。当てはまる人が少ない＝索引が効く場合。followedByMe は省略）'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM (
  SELECT u.id, u.username, u.display_name,
         CASE
           WHEN LOWER(u.username) = LOWER('perf0042') THEN 0
           WHEN u.username ILIKE 'perf0042' || '%' THEN 1
           ELSE 2
         END AS match_rank
  FROM users u
  WHERE u.username ILIKE '%' || 'perf0042' || '%'
     OR u.display_name ILIKE '%' || 'perf0042' || '%'
) found
ORDER BY found.match_rank, LOWER(found.username)
LIMIT 21;
