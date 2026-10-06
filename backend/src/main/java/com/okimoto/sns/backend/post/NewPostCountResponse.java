package com.okimoto.sns.backend.post;

/** 新しい投稿の件数（A-16・A-17）。PostService.NEW_COUNT_LIMIT 件で打ち切る。 */
public record NewPostCountResponse(int count) {}
