package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.user.User;

/** ログイン中のユーザーの情報（A-01〜A-03 のレスポンスの user）。 */
public record UserResponse(long id, String username, String displayName, String iconUrl) {

  /**
   * @param iconUrl アイコン画像の URL（ImageStorage#urlOf で作る）。アイコンを設定していなければ null（画面は頭文字を表示）
   */
  public static UserResponse from(User user, String iconUrl) {
    return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), iconUrl);
  }
}
