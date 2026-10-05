-- 投稿（docs/database.md「posts（投稿）」）
-- 画像（post_images）は画像投稿の実装時に追加する。今回はテキストのみ
CREATE TABLE posts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    content    VARCHAR(280) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    edited_at  TIMESTAMPTZ
);

-- フォロー中タイムライン・プロフィールの投稿一覧（ユーザーで絞って新しい順）
CREATE INDEX idx_posts_user_id_created_at ON posts (user_id, created_at DESC);

-- 全体タイムライン（絞り込みなしで新しい順）
CREATE INDEX idx_posts_created_at_id ON posts (created_at DESC, id DESC);
