-- ユーザー検索の部分一致（username・display_name の ILIKE '%…%'）を索引で引けるようにする（Issue #131）。
-- 部分一致は B-tree の索引を使えず、索引がないと users を全件読んでしまう（docs/performance-test-results.md）。
-- pg_trgm は文字列を 3 文字ずつの組（trigram）に分けて索引にする拡張。GIN の索引は LIKE・ILIKE の部分一致に使える。
-- キーワードが 2 文字以下のときは 3 文字の組を作れないので、これまでどおり全件を読む。
-- pg_trgm は「信頼できる拡張」なので、スーパーユーザーでなくても DB の CREATE 権限があれば作れる（RDS も同じ）。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_users_username_trgm ON users USING gin (username gin_trgm_ops);
CREATE INDEX idx_users_display_name_trgm ON users USING gin (display_name gin_trgm_ops);
