-- ユーザー（docs/database.md「users（ユーザー）」）
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(15)  NOT NULL,
    display_name  VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    bio           VARCHAR(160),
    icon_key      VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_username_format CHECK (username ~ '^[A-Za-z0-9_]{4,15}$'),
    CONSTRAINT ck_users_email_lowercase CHECK (email = LOWER(email))
);

-- @ユーザー名は大文字・小文字を区別せずに重複を防ぐ（Yamada と yamada は同じとみなす）
CREATE UNIQUE INDEX uq_users_username_lower ON users (LOWER(username));

-- ユーザー検索でキーワードが空のとき（最近参加したユーザー）に使う
CREATE INDEX idx_users_created_at ON users (created_at DESC);
