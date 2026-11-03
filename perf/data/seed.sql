-- パフォーマンステストのデータを入れる（docs/performance-test-plan.md「4. テストデータ」）。
-- perf/setup-db.sh から、規模（基準・10倍）に合わせた psql の変数を付けて実行する。直接は実行しない。
--
-- 変数:
--   users            ユーザー数（特別なユーザー2人を含む）
--   posts            投稿数（特別な投稿2件を含む）
--   follows_per_user 1人がランダムにフォローする人数（重なりは捨てるので、実際は少し減る）
--   likes            ランダムに付けるいいねの数（重なりは捨てる）
--   comments         ランダムに付けるコメントの数
--   heavy_follows    perf00001 がフォローする人数
--   star_followers   perf00002 のフォロワーの数
--   hot_likes        投稿 1 に付けるいいねの数
--   hot_comments     投稿 2 に付けるコメントの数
--
-- 特別なデータ（偏りのあるデータ。k6 のシナリオから ID・ユーザー名で使う）:
--   perf00001  たくさんフォローしている人（フォロー中のタイムラインが一番重い）
--   perf00002  フォロワーの多い人
--   投稿 1     perf00002 の投稿。いいねが多い
--   投稿 2     perf00002 の投稿。コメントが多い
--
-- ユーザーはすべて perf<5桁の番号>（メールアドレスは perf<番号>@example.com）、パスワードは password123。
-- 同じ乱数の種を使うので、何度作り直しても同じデータになる（変更の前と後を同じデータで比べるため）。
-- 投稿日時などは「実行した時刻から過去30日」なので、日時だけは実行するたびにずれる。

\set ON_ERROR_STOP on

-- 開発用の DB（sns）などに間違って入れないよう、sns_perf 以外では止める
DO $$
BEGIN
    IF current_database() <> 'sns_perf' THEN
        RAISE EXCEPTION 'この SQL は sns_perf でだけ実行できます（今の DB: %）', current_database();
    END IF;
END
$$;

BEGIN;

-- 前のデータを消し、ID を 1 から振り直す。テーブルが増えても直さなくてよいよう、Flyway の履歴以外を全部消す
DO $$
DECLARE
    tables text;
BEGIN
    SELECT string_agg(format('%I', tablename), ', ')
      INTO tables
      FROM pg_tables
     WHERE schemaname = 'public'
       AND tablename <> 'flyway_schema_history';
    EXECUTE 'TRUNCATE ' || tables || ' RESTART IDENTITY CASCADE';
END
$$;

SELECT setseed(0.125);

-- ユーザー。投稿より前（31日より前）に、1分おきに登録したことにする
INSERT INTO users (username, display_name, email, password_hash, bio, created_at, updated_at)
SELECT 'perf' || lpad(n::text, 5, '0'),
       '性能テスト ' || n,
       'perf' || lpad(n::text, 5, '0') || '@example.com',
       -- password123 の BCrypt のハッシュ（強さ 10。アプリの BCryptPasswordEncoder の既定と同じ）
       '$2a$10$w2MWtODBTa.hQ2xW./6fR.MjhbPodj2bRhFrpdTaVZ3JCZ6xurrEy',
       CASE WHEN n % 3 = 0 THEN '性能テスト用のユーザーです。' END,
       t, t
  FROM generate_series(1, :users) AS n,
       LATERAL (SELECT now() - interval '31 days' - (:users - n) * interval '1 minute' AS t) AS c
 ORDER BY n;

-- 特別な投稿（ID 1・2）。タイムラインの最初のページに出るよう、新しい日時にする
INSERT INTO posts (user_id, content, created_at, updated_at)
VALUES (2, '性能テスト：いいねの多い投稿', now() - interval '1 hour', now() - interval '1 hour'),
       (2, '性能テスト：コメントの多い投稿', now() - interval '2 hours', now() - interval '2 hours');

-- 残りの投稿。過去30日にばらつかせ、ID が日時の順になるよう古い順に入れる
INSERT INTO posts (user_id, content, created_at, updated_at)
SELECT user_id, '性能テストの投稿です。' || repeat('あ', (random() * 100)::int), t, t
  FROM (SELECT 1 + floor(random() * :users)::bigint AS user_id,
               now() - random() * interval '30 days' AS t
          FROM generate_series(1, :posts - 2)) AS r
 ORDER BY t;

-- フォロー：perf00001 がたくさんの人をフォローする
INSERT INTO follows (follower_id, followee_id, created_at)
SELECT 1, n, now() - random() * interval '30 days'
  FROM generate_series(2, :heavy_follows + 1) AS n;

-- フォロー：たくさんの人が perf00002 をフォローする
INSERT INTO follows (follower_id, followee_id, created_at)
SELECT n, 2, now() - random() * interval '30 days'
  FROM generate_series(1, :star_followers + 1) AS n
 WHERE n <> 2
ON CONFLICT DO NOTHING;

-- フォロー：1人ずつランダムな相手をフォローする
INSERT INTO follows (follower_id, followee_id, created_at)
SELECT follower_id, followee_id, now() - random() * interval '30 days'
  FROM (SELECT u AS follower_id, 1 + floor(random() * :users)::bigint AS followee_id
          FROM generate_series(1, :users) AS u,
               generate_series(1, :follows_per_user)) AS r
 WHERE follower_id <> followee_id
ON CONFLICT DO NOTHING;

-- いいね：投稿 1 にたくさん付ける
INSERT INTO likes (post_id, user_id, created_at)
SELECT 1, n, now() - interval '1 hour' + random() * interval '1 hour'
  FROM generate_series(1, :hot_likes) AS n;

-- いいね：ランダムな投稿に付ける（日時は投稿のあと）
INSERT INTO likes (post_id, user_id, created_at)
SELECT p.id, r.user_id, p.created_at + random() * (now() - p.created_at)
  FROM (SELECT 1 + floor(random() * :posts)::bigint AS post_id,
               1 + floor(random() * :users)::bigint AS user_id
          FROM generate_series(1, :likes)) AS r
  JOIN posts AS p ON p.id = r.post_id
ON CONFLICT DO NOTHING;

-- コメント：投稿 2 にたくさん付ける
INSERT INTO comments (post_id, user_id, content, created_at)
SELECT 2, 1 + (n % :users), '性能テストのコメント ' || n,
       now() - interval '2 hours' + n * (interval '2 hours' / (:hot_comments + 1))
  FROM generate_series(1, :hot_comments) AS n;

-- コメント：ランダムな投稿に付ける（日時は投稿のあと）
INSERT INTO comments (post_id, user_id, content, created_at)
SELECT p.id, r.user_id, '性能テストのコメントです。', p.created_at + random() * (now() - p.created_at)
  FROM (SELECT 1 + floor(random() * :posts)::bigint AS post_id,
               1 + floor(random() * :users)::bigint AS user_id
          FROM generate_series(1, :comments)) AS r
  JOIN posts AS p ON p.id = r.post_id;

COMMIT;

-- 大量に入れたあとは統計情報を取り直す（古い統計のままだと、実行計画が実際と違ってしまう）
VACUUM ANALYZE;

-- 入ったデータの件数
SELECT 'users' AS "テーブル", count(*) AS "件数" FROM users
UNION ALL SELECT 'posts', count(*) FROM posts
UNION ALL SELECT 'follows', count(*) FROM follows
UNION ALL SELECT 'likes', count(*) FROM likes
UNION ALL SELECT 'comments', count(*) FROM comments;

SELECT round(count(*)::numeric / (SELECT count(*) FROM users), 1) AS "1人の平均フォロー数" FROM follows;
