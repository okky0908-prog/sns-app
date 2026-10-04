package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.user.User;

/** ログイン中のユーザーの情報（A-01〜A-03 のレスポンスの user）。 */
public record UserResponse(long id, String username, String displayName, String iconUrl) {

  public static UserResponse from(User user) {
    // アイコン画像（S3）はプロフィール編集の実装時に対応する。それまでは常に null（デフォルト画像を表示）
    return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), null);
  }
}
