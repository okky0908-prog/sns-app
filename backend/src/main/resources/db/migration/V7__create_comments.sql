-- コメント（docs/database.md「comments（コメント）」）。編集できないので updated_at は持たない
CREATE TABLE comments (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id    BIGINT       NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    content    VARCHAR(280) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

-- 投稿詳細のコメント一覧（古い順・カーソルで続きを取る）、コメント数の集計、投稿削除時の CASCADE
CREATE INDEX idx_comments_post_id_created_at_id ON comments (post_id, created_at, id);

-- ユーザー削除時の CASCADE
CREATE INDEX idx_comments_user_id ON comments (user_id);
