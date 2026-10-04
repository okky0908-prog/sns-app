package com.okimoto.sns.backend.auth;

/**
 * ログイン中のユーザー。JwtAuthenticationFilter がトークンから作り、コントローラでは {@code @AuthenticationPrincipal
 * AuthenticatedUser user} で受け取る。
 */
public record AuthenticatedUser(long id) {}
