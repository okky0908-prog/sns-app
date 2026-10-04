-- リフレッシュトークン（docs/database.md「refresh_tokens（リフレッシュトークン）」）
-- トークンそのものは保存せず、SHA-256 のハッシュだけを保存する（DB が漏れてもトークンとして使えないように）
CREATE TABLE refresh_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash CHAR(64)    NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

-- ユーザーのリフレッシュトークンをまとめて無効にするとき（使い回しを検知したとき）と、ユーザー削除時の CASCADE に使う
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
