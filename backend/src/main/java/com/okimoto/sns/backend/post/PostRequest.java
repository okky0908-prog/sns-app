package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.web.TextLength;

/**
 * 投稿の編集（A-13）のリクエスト（JSON）。作成（A-11）は画像も送るので multipart で受け取る（PostController）。
 *
 * <p>本文は前後の空白・改行を除いて280文字まで（絵文字も1文字と数える）。空にできるのは画像のある投稿だけ（PostService で確かめる）。
 */
public record PostRequest(
    @TextLength(max = 280, message = PostService.CONTENT_TOO_LONG) String content) {}
