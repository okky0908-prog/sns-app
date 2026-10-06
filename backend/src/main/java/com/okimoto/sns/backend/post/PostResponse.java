package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.auth.UserResponse;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 投稿のレスポンス（docs/api.md「Post（投稿）」）。
 *
 * <p>画像（images）は画像投稿の実装までは常に空。いいね数・コメント数などは、それぞれの機能の実装時に追加する。
 *
 * @param editedAt 本文を編集した日時。null なら未編集（画面に「編集済み」を出すかどうか）
 * @param mine ログイン中のユーザーの投稿か（画面に「…」メニューを出すかどうか）
 */
public record PostResponse(
    long id,
    String content,
    List<PostImageResponse> images,
    UserResponse author,
    OffsetDateTime editedAt,
    OffsetDateTime createdAt,
    boolean mine) {

  public record PostImageResponse(String url, int sortOrder) {}

  static PostResponse from(Post post, long me) {
    // アイコン画像（S3）はプロフィール編集の実装時に対応する。それまでは常に null
    UserResponse author =
        new UserResponse(
            post.getUserId(), post.getAuthorUsername(), post.getAuthorDisplayName(), null);
    return new PostResponse(
        post.getId(),
        post.getContent(),
        List.of(),
        author,
        post.getEditedAt(),
        post.getCreatedAt(),
        post.getUserId() == me);
  }
}
