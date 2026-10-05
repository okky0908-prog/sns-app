-- 再発行（ローテーション）で新しいトークンに交換した日時（docs/database.md「refresh_tokens」）
-- revoked_at だけではログアウト・使い回し検知による無効化と区別できないため追加する。
-- 交換から短い時間（猶予時間）のうちに同じトークンがもう一度使われたときは、
-- 2つのタブが同時に再発行したなどの「同時のリクエスト」とみなして、全無効化しない
ALTER TABLE refresh_tokens ADD COLUMN rotated_at TIMESTAMPTZ;
