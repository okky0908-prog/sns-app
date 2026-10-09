package com.okimoto.sns.backend.comment;

import com.okimoto.sns.backend.auth.UserResponse;
import java.time.OffsetDateTime;

/**
 * コメントのレスポンス（A-30 の items の各要素）。
 *
 * @param mine ログイン中のユーザーのコメントか（画面に「削除」を出すかどうか）
 */
public record CommentResponse(
    long id, String content, UserResponse author, OffsetDateTime createdAt, boolean mine) {

  /** authorIconUrl はコメントした人のアイコン画像の URL（ImageStorage#urlOf で作る。なければ null） */
  static CommentResponse from(Comment comment, long me, String authorIconUrl) {
    UserResponse author =
        new UserResponse(
            comment.getUserId(),
            comment.getAuthorUsername(),
            comment.getAuthorDisplayName(),
            authorIconUrl);
    return new CommentResponse(
        comment.getId(),
        comment.getContent(),
        author,
        comment.getCreatedAt(),
        comment.getUserId() == me);
  }
}
