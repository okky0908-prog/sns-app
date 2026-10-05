package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.web.TextLength;

/**
 * 投稿の作成（A-11）・編集（A-13）のリクエスト。
 *
 * <p>本文は前後の空白・改行を除いて1〜280文字（絵文字も1文字と数える）。画像の添付は今回は未対応のため、本文は必須。
 */
public record PostRequest(
    @TextLength(min = 1, max = 280, message = "本文は1〜280文字で入力してください") String content) {}
