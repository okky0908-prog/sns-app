package com.okimoto.sns.backend.comment;

import com.okimoto.sns.backend.auth.UserResponse;
import java.time.OffsetDateTime;

/**
 * コメント投稿（A-31）のレスポンス。コメントの中身に加えて、画面でコメント数をすぐ更新できるよう投稿後のコメント数も返す。
 *
 * @param commentCount 投稿後のその投稿のコメント数
 */
public record CreatedCommentResponse(
    long id,
    String content,
    UserResponse author,
    OffsetDateTime createdAt,
    boolean mine,
    long commentCount) {

  static CreatedCommentResponse of(CommentResponse comment, long commentCount) {
    return new CreatedCommentResponse(
        comment.id(),
        comment.content(),
        comment.author(),
        comment.createdAt(),
        comment.mine(),
        commentCount);
  }
}
