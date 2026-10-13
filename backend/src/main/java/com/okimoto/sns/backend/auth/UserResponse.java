package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ログイン中のユーザーの情報（A-01〜A-03 のレスポンスの user）。投稿・コメントの投稿者（author）にも使う。
 *
 * @param iconUrl アイコン画像の URL。設定していなければ null（画面は頭文字を表示）
 */
public record UserResponse(
    long id, String username, String displayName, @Schema(nullable = true) String iconUrl) {

  /**
   * @param iconUrl アイコン画像の URL（ImageStorage#urlOf で作る）。アイコンを設定していなければ null（画面は頭文字を表示）
   */
  public static UserResponse from(User user, String iconUrl) {
    return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), iconUrl);
  }
}
