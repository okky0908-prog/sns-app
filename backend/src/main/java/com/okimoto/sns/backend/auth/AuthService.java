package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新規登録・ログイン・トークンの再発行・ログアウト・ログイン中のユーザー情報の取得（docs/feature-specs/01_auth.md）。 */
@Service
public class AuthService {

  static final String USERNAME_TAKEN = "このユーザー名はすでに使われています";
  static final String EMAIL_TAKEN = "このメールアドレスはすでに登録されています";
  static final String PASSWORD_TOO_LONG = "パスワードが長すぎます（全角文字は1文字を3バイトとして、72バイトまで）";

  /** BCrypt が扱えるのは72バイトまで */
  private static final int PASSWORD_MAX_BYTES = 72;

  private final UserMapper userMapper;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  /** 存在しないメールアドレスでログインされたときに照合に使う、ダミーのハッシュ */
  private final String dummyPasswordHash;

  public AuthService(
      UserMapper userMapper,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      RefreshTokenService refreshTokenService,
      Clock clock) {
    this.userMapper = userMapper;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.refreshTokenService = refreshTokenService;
    this.clock = clock;
    this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
  }

  @Transactional
  public AuthResult signup(SignupRequest request) {
    String username = request.username();
    String email = normalizeEmail(request.email());
    if (!fitsBcrypt(request.password())) {
      throw ApiException.badRequest(
          List.of(new ApiError.FieldError("password", PASSWORD_TOO_LONG)));
    }

    List<ApiError.FieldError> conflicts = new ArrayList<>();
    if (userMapper.existsByUsernameIgnoreCase(username)) {
      conflicts.add(new ApiError.FieldError("username", USERNAME_TAKEN));
    }
    if (userMapper.existsByEmail(email)) {
      conflicts.add(new ApiError.FieldError("email", EMAIL_TAKEN));
    }
    if (!conflicts.isEmpty()) {
      throw ApiException.conflict(conflicts);
    }

    User user =
        new User(
            username,
            request.displayName().strip(),
            email,
            passwordEncoder.encode(request.password()),
            OffsetDateTime.now(clock));
    try {
      userMapper.insert(user);
    } catch (DuplicateKeyException e) {
      // 上の重複チェックのあと、同時に同じ内容で登録された場合。DB の一意制約で止まったものを 409 にする
      boolean emailTaken = String.valueOf(e.getMessage()).contains("uq_users_email");
      throw ApiException.conflict(
          List.of(
              emailTaken
                  ? new ApiError.FieldError("email", EMAIL_TAKEN)
                  : new ApiError.FieldError("username", USERNAME_TAKEN)));
    }
    return issueTokens(user);
  }

  @Transactional
  public AuthResult login(LoginRequest request) {
    Optional<User> user = userMapper.findByEmail(normalizeEmail(request.email()));
    // ユーザーがいなくても同じようにハッシュの照合をして、応答時間から登録済みのメールアドレスを推測されないようにする
    String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
    boolean matches =
        fitsBcrypt(request.password()) && passwordEncoder.matches(request.password(), hash);
    if (user.isEmpty() || !matches) {
      // どちらが違うかは教えない
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }
    return issueTokens(user.get());
  }

  /**
   * リフレッシュトークンを新しいものに交換し、アクセストークンを再発行する。
   *
   * <p>トランザクションは RefreshTokenService#rotate の中で完結させる（無効なトークンのときに投げる例外で、使い回し検知の処理がロールバックされないように）。
   */
  public AuthResult refresh(String refreshToken) {
    RefreshTokenService.Rotation rotation =
        refreshTokenService
            .rotate(refreshToken)
            .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
    User user =
        userMapper
            .findById(rotation.userId())
            .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED));
    return new AuthResult(
        jwtService.issue(user.getId()), rotation.refreshToken(), UserResponse.from(user));
  }

  /** ログアウト。リフレッシュトークンを無効にする（アクセストークンは期限の15分が過ぎるまで有効なまま）。 */
  @Transactional
  public void logout(String refreshToken) {
    refreshTokenService.revoke(refreshToken);
  }

  @Transactional(readOnly = true)
  public UserResponse me(long userId) {
    return userMapper
        .findById(userId)
        .map(UserResponse::from)
        // トークンは正しいが、ユーザーが存在しない（削除された）場合
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }

  private AuthResult issueTokens(User user) {
    return new AuthResult(
        jwtService.issue(user.getId()),
        refreshTokenService.issue(user.getId()),
        UserResponse.from(user));
  }

  /** メールアドレスは小文字にそろえる（Yamada@Example.com と yamada@example.com を同じとみなす） */
  private static String normalizeEmail(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  private static boolean fitsBcrypt(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
  }
}
