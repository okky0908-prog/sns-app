package com.okimoto.sns.backend.user;

/**
 * プロフィールのレスポンス（A-60）。
 *
 * @param iconUrl アイコン画像の URL。設定していなければ null（画面は頭文字を表示）
 * @param followedByMe ログイン中のユーザーがこのユーザーをフォローしているか
 * @param me 自分のプロフィールか（「プロフィールを編集」とフォローボタンのどちらを出すか）
 */
public record ProfileResponse(
    long id,
    String username,
    String displayName,
    String bio,
    String iconUrl,
    long followingCount,
    long followerCount,
    boolean followedByMe,
    boolean me) {

  static ProfileResponse from(Profile profile, long me, String iconUrl) {
    return new ProfileResponse(
        profile.getId(),
        profile.getUsername(),
        profile.getDisplayName(),
        profile.getBio(),
        iconUrl,
        profile.getFollowingCount(),
        profile.getFollowerCount(),
        profile.isFollowedByMe(),
        profile.getId() == me);
  }
}
