-- 投稿の画像（docs/database.md「post_images（投稿画像）」）。画像ファイルは S3 に置き、ここには S3 のキーだけを持つ
CREATE TABLE post_images (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id    BIGINT       NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    image_key  VARCHAR(255) NOT NULL,
    sort_order SMALLINT     NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    -- 1つの投稿に4枚まで（表示順は 1〜4）。同じ投稿の中で表示順は重ならない
    CONSTRAINT ck_post_images_sort_order CHECK (sort_order BETWEEN 1 AND 4),
    -- 先頭が post_id なので、投稿 ID で画像をまとめて取るとき・投稿削除時の CASCADE にもこのインデックスを使う
    CONSTRAINT uq_post_images_post_sort UNIQUE (post_id, sort_order)
);
