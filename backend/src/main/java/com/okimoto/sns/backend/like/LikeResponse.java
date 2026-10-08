package com.okimoto.sns.backend.like;

/**
 * いいね・取り消しのレスポンス（A-40・A-41）。画面は押した直後に表示を変え、この値で上書きする（その間のほかの人のいいねも反映するため）。
 *
 * @param likeCount いいね数
 * @param likedByMe ログイン中のユーザーがいいね済みか
 */
public record LikeResponse(long likeCount, boolean likedByMe) {}
