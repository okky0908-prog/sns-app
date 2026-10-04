package com.okimoto.sns.backend.auth;

/**
 * 新規登録・ログイン・再発行のレスポンス（A-01・A-02・A-04）。
 *
 * <p>リフレッシュトークンは JavaScript から読めないよう、JSON には入れず HttpOnly Cookie で返す。
 */
public record AuthResponse(String accessToken, UserResponse user) {}
