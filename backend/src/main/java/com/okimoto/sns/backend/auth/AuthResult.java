package com.okimoto.sns.backend.auth;

/**
 * 新規登録・ログイン・再発行の結果。
 *
 * <p>accessToken と user はレスポンスの JSON（AuthResponse）に、refreshToken は HttpOnly Cookie に入れて返す。
 */
public record AuthResult(String accessToken, String refreshToken, UserResponse user) {

  public AuthResponse toResponse() {
    return new AuthResponse(accessToken, user);
  }
}
