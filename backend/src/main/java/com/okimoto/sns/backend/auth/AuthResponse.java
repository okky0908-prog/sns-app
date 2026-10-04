package com.okimoto.sns.backend.auth;

/** 新規登録・ログインのレスポンス（A-01・A-02）。 */
public record AuthResponse(String token, UserResponse user) {}
