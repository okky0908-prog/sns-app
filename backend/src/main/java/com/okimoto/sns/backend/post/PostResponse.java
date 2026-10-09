package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.auth.UserResponse;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 投稿のレスポンス（docs/api.md「Post（投稿）」）。
 *
 * <p>画像（images）は画像投稿の実装までは常に空。
 *
 * @param likeCount いいね数
 * @param likedByMe ログイン中のユーザーがいいね済みか（ハートの色）
 * @param commentCount コメント数
 * @param editedAt 本文を編集した日時。null なら未編集（画面に「編集済み」を出すかどうか）
 * @param mine ログイン中のユーザーの投稿か（画面に「…」メニューを出すかどうか）
 */
public record PostResponse(
    long id,
    String content,
    List<PostImageResponse> images,
    UserResponse author,
    long likeCount,
    boolean likedByMe,
    long commentCount,
    OffsetDateTime editedAt,
    OffsetDateTime createdAt,
    boolean mine) {

  public record PostImageResponse(String url, int sortOrder) {}

  /** authorIconUrl は投稿者のアイコン画像の URL（ImageStorage#urlOf で作る。なければ null） */
  static PostResponse from(Post post, long me, String authorIconUrl) {
    UserResponse author =
        new UserResponse(
            post.getUserId(), post.getAuthorUsername(), post.getAuthorDisplayName(), authorIconUrl);
    return new PostResponse(
        post.getId(),
        post.getContent(),
        List.of(),
        author,
        post.getLikeCount(),
        post.isLikedByMe(),
        post.getCommentCount(),
        post.getEditedAt(),
        post.getCreatedAt(),
        post.getUserId() == me);
  }
}
