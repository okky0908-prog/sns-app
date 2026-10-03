package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.web.TextLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新規登録のリクエスト（A-01）。チェック内容とメッセージは docs/feature-specs/01_auth.md のとおり。 */
public record SignupRequest(
    @NotNull(message = USERNAME_MESSAGE)
        @Pattern(regexp = "^[A-Za-z0-9_]{4,15}$", message = USERNAME_MESSAGE)
        String username,
    @TextLength(min = 1, max = 50, message = "表示名は1〜50文字で入力してください") String displayName,
    @NotBlank(message = EMAIL_MESSAGE)
        @Email(regexp = ".+@.+\\..+", message = EMAIL_MESSAGE)
        @Size(max = 255, message = EMAIL_MESSAGE)
        String email,
    @NotNull(message = PASSWORD_MIN_MESSAGE)
        @Size(min = 8, message = PASSWORD_MIN_MESSAGE)
        @Size(max = 72, message = "パスワードは72文字以内で入力してください")
        String password) {

  static final String USERNAME_MESSAGE = "ユーザー名は半角英数字と_で4〜15文字で入力してください";
  static final String EMAIL_MESSAGE = "メールアドレスの形式が正しくありません";
  static final String PASSWORD_MIN_MESSAGE = "パスワードは8文字以上で入力してください";
}
