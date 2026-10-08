package com.okimoto.sns.backend.comment;

import com.okimoto.sns.backend.web.TextLength;

/** コメント投稿（A-31）のリクエスト。本文は前後の空白・改行を除いて1〜280文字（数え方は投稿と同じ）。 */
public record CommentRequest(
    @TextLength(min = 1, max = 280, message = "コメントは1〜280文字で入力してください") String content) {}
