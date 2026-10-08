-- いいね（docs/database.md「likes（いいね）」）
CREATE TABLE likes (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id    BIGINT      NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    -- 同じ人が同じ投稿に2回いいねできない（連打・同時のリクエストでも DB で止める）。
    -- 先頭が post_id なので、いいね数の集計・いいね済みかの判定にもこのインデックスを使う
    CONSTRAINT uq_likes_post_user UNIQUE (post_id, user_id)
);

-- ユーザー削除時の CASCADE
CREATE INDEX idx_likes_user_id ON likes (user_id);
