package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.web.ApiException;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 認証の API（docs/api.md の A-01〜A-05）。
 *
 * <p>アクセストークンは JSON で返し、リフレッシュトークンは HttpOnly Cookie で返す。Cookie は /api/auth の下（再発行・ログアウト）にだけ送られる。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  static final String REFRESH_TOKEN_COOKIE = "refresh_token";
  private static final String COOKIE_PATH = "/api/auth";

  private final AuthService authService;
  private final RefreshTokenProperties refreshTokenProperties;

  public AuthController(AuthService authService, RefreshTokenProperties refreshTokenProperties) {
    this.authService = authService;
    this.refreshTokenProperties = refreshTokenProperties;
  }

  /** A-01 新規登録。登録後はそのままログイン状態にするため、トークンも返す。 */
  @PostMapping("/signup")
  public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
    return withRefreshToken(HttpStatus.CREATED, authService.signup(request));
  }

  /** A-02 ログイン。 */
  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return withRefreshToken(HttpStatus.OK, authService.login(request));
  }

  /** A-03 ログイン中のユーザー情報（アクセストークンが必要）。 */
  @GetMapping("/me")
  public UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
    return authService.me(user.id());
  }

  /** A-04 アクセストークンの再発行。リフレッシュトークンも新しいものに交換する。 */
  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refresh(
      @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, AuthService.SESSION_EXPIRED);
    }
    return withRefreshToken(HttpStatus.OK, authService.refresh(refreshToken));
  }

  /** A-05 ログアウト。リフレッシュトークンを無効にし、Cookie を消す。 */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
    if (refreshToken != null && !refreshToken.isBlank()) {
      authService.logout(refreshToken);
    }
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, refreshTokenCookie("", Duration.ZERO).toString())
        .build();
  }

  private ResponseEntity<AuthResponse> withRefreshToken(HttpStatus status, AuthResult result) {
    ResponseCookie cookie =
        refreshTokenCookie(result.refreshToken(), refreshTokenProperties.expiration());
    return ResponseEntity.status(status)
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(result.toResponse());
  }

  /**
   * リフレッシュトークンの Cookie。
   *
   * <ul>
   *   <li>HttpOnly：JavaScript から読めない（XSS で盗まれにくい）
   *   <li>SameSite=Strict：他のサイトからのリクエストには付かない（CSRF 対策）
   *   <li>Secure：HTTPS のときだけ送る（本番。ローカル開発は HTTP なので設定で切り替える）
   * </ul>
   */
  private ResponseCookie refreshTokenCookie(String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
        .httpOnly(true)
        .secure(refreshTokenProperties.cookieSecure())
        .sameSite("Strict")
        .path(COOKIE_PATH)
        .maxAge(maxAge)
        .build();
  }
}
