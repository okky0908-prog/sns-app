-- フォロー（docs/database.md「follows（フォロー）」）
-- フォロー中タイムラインで使うため、フォローの画面より先にテーブルだけ作る
CREATE TABLE follows (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    follower_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL,
    -- 同じ相手を2回フォローできない（このインデックスは「自分がフォロー中の人」の検索にも使う）
    CONSTRAINT uq_follows_follower_followee UNIQUE (follower_id, followee_id),
    -- 自分自身はフォローできない
    CONSTRAINT ck_follows_not_self CHECK (follower_id <> followee_id)
);

-- フォロワー一覧・フォロワー数の集計、ユーザー削除時の CASCADE
CREATE INDEX idx_follows_followee_id ON follows (followee_id);
