package com.okimoto.sns.backend.user;

/**
 * プロフィールのレスポンス（A-60）。
 *
 * @param iconUrl アイコン画像の URL。アイコンのアップロードは画像投稿の実装時に作るので、それまでは常に null
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

  static ProfileResponse from(Profile profile, long me) {
    return new ProfileResponse(
        profile.getId(),
        profile.getUsername(),
        profile.getDisplayName(),
        profile.getBio(),
        null,
        profile.getFollowingCount(),
        profile.getFollowerCount(),
        profile.isFollowedByMe(),
        profile.getId() == me);
  }
}
