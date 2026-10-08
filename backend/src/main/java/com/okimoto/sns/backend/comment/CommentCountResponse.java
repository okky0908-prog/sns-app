package com.okimoto.sns.backend.comment;

/** コメント削除（A-32）のレスポンス。削除後のその投稿のコメント数。 */
public record CommentCountResponse(long commentCount) {}
