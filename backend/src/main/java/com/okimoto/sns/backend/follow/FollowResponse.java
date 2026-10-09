package com.okimoto.sns.backend.follow;

/**
 * フォロー・フォロー解除のレスポンス（A-50・A-51）。画面は押した直後に表示を変え、この値で上書きする。
 *
 * @param following ログイン中のユーザーが相手をフォローしているか
 * @param followerCount 相手のフォロワー数
 */
public record FollowResponse(boolean following, long followerCount) {}
