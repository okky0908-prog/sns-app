package com.okimoto.sns.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** ログインのリクエスト（A-02）。 */
public record LoginRequest(
    @NotBlank(message = "メールアドレスを入力してください") String email,
    @NotEmpty(message = "パスワードを入力してください") String password) {}
