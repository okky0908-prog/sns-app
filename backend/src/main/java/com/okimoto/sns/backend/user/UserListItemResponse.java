package com.okimoto.sns.backend.user;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ユーザー一覧の1行（A-52・A-53 フォロー中・フォロワー一覧、A-70 ユーザー検索で共通）。
 *
 * @param bio 自己紹介。設定していなければ null
 * @param iconUrl アイコン画像の URL。設定していなければ null（画面は頭文字を表示）
 * @param followedByMe ログイン中の自分がこのユーザーをフォローしているか（一覧を見ている相手ではなく、自分から見た状態）
 * @param me 自分自身か（フォローボタンを出さない）
 */
public record UserListItemResponse(
    long id,
    String username,
    String displayName,
    @Schema(nullable = true) String bio,
    @Schema(nullable = true) String iconUrl,
    boolean followedByMe,
    boolean me) {

  public static UserListItemResponse of(
      long id,
      String username,
      String displayName,
      String bio,
      String iconUrl,
      boolean followedByMe,
      long me) {
    return new UserListItemResponse(
        id, username, displayName, bio, iconUrl, followedByMe, id == me);
  }
}
