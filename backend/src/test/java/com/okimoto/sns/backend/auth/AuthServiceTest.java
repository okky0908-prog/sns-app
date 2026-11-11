package com.okimoto.sns.backend.auth;

import static com.okimoto.sns.backend.support.ApiErrors.assertApiError;
import static com.okimoto.sns.backend.support.ApiErrors.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionOperations;

/**
 * AuthService の単体テスト（Mapper・トークンの発行・S3 は偽物。パスワードのハッシュは本物の BCrypt を強度を下げて使う）。
 *
 * <ul>
 *   <li>デシジョンテーブル（新規登録）：パスワードが72バイト超 → 400、予約語 → 400、ユーザー名・メールアドレスの重複（片方・両方）→ 409、同時の登録で DB が弾いた →
 *       409
 *   <li>同値分割（ログイン）：メールアドレスがない・パスワードが違う → どちらも同じ 401（どちらが違うかは教えない）
 *   <li>境界値分析：パスワード 72・73バイト（日本語は1文字3バイト。24文字＝72バイトは OK、25文字＝75バイトはエラー）
 *   <li>エラー推測：メールアドレスの大文字・前後の空白、予約語の大文字、再発行のときにユーザーが削除されていた
 * </ul>
 */
class AuthServiceTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");

  private final UserMapper userMapper = mock(UserMapper.class);
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
  private final JwtService jwtService = mock(JwtService.class);
  private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final PasswordHashingGate passwordHashingGate =
      spy(new PasswordHashingGate(new PasswordHashingProperties(2, Duration.ofSeconds(1))));
  private final AuthService service =
      new AuthService(
          userMapper,
          passwordEncoder,
          jwtService,
          refreshTokenService,
          imageStorage,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
          new LoginAttemptLimiter(
              Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
              new LoginAttemptProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(15))),
          passwordHashingGate);

  @BeforeEach
  void setUp() {
    when(jwtService.issue(anyLong())).thenReturn("access-token");
    when(refreshTokenService.issue(anyLong())).thenReturn("refresh-token");
  }

  private static SignupRequest signup(String username, String email, String password) {
    return new SignupRequest(username, " 山田太郎 ", email, password);
  }

  private User savedUser(String password) {
    User user =
        new User("yamada", "山田太郎", "yamada@example.com", passwordEncoder.encode(password), NOW);
    user.setId(7L);
    return user;
  }

  @Nested
  class 新規登録 {

    @Test
    void 登録すると_メールアドレスは小文字_表示名は前後の空白を除き_パスワードはハッシュで保存しトークンを返す() {
      doAnswer(
              invocation -> {
                invocation.<User>getArgument(0).setId(7L);
                return null;
              })
          .when(userMapper)
          .insert(any());

      AuthResult result = service.signup(signup("Yamada", " Yamada@Example.COM ", "password123"));

      verify(userMapper)
          .insert(
              argThat(user ->
                  user.getUsername().equals("Yamada")
                      && user.getEmail().equals("yamada@example.com")
                      && user.getDisplayName().equals("山田太郎")
                      && !user.getPasswordHash().equals("password123")
                      && passwordEncoder.matches("password123", user.getPasswordHash())
                      && user.getCreatedAt().equals(NOW)));
      assertThat(result.accessToken()).isEqualTo("access-token");
      assertThat(result.refreshToken()).isEqualTo("refresh-token");
      assertThat(result.user().id()).isEqualTo(7);
    }

    @Test
    void パスワードは72バイトまで_日本語24文字は登録でき_25文字は400() {
      doAnswer(
              invocation -> {
                invocation.<User>getArgument(0).setId(7L);
                return null;
              })
          .when(userMapper)
          .insert(any());

      service.signup(signup("yamada", "y@example.com", "あ".repeat(24))); // 72バイト
      assertFieldError(
          () -> service.signup(signup("yamada", "y@example.com", "あ".repeat(25))), // 75バイト
          "password",
          AuthService.PASSWORD_TOO_LONG);
    }

    @ParameterizedTest(name = "ユーザー名「{0}」は使えない")
    @ValueSource(strings = {"search", "SEARCH", "Search"})
    void 予約語のユーザー名は大文字小文字によらず400で_登録しない(String username) {
      assertFieldError(
          () -> service.signup(signup(username, "y@example.com", "password123")),
          "username",
          AuthService.USERNAME_RESERVED);
      verify(userMapper, never()).insert(any());
    }

    @Test
    void ハッシュ化の空きがなければ503にし_保存しない() {
      doThrow(new ApiException(ErrorCode.SERVER_BUSY)).when(passwordHashingGate).run(any());

      assertApiError(
          () -> service.signup(signup("yamada", "yamada@example.com", "password123")),
          ErrorCode.SERVER_BUSY);
      verify(userMapper, never()).insert(any());
    }

    @Test
    void ユーザー名とメールアドレスが両方使われていたら_両方を409で返す() {
      when(userMapper.existsByUsernameIgnoreCase("yamada")).thenReturn(true);
      when(userMapper.existsByEmail("yamada@example.com")).thenReturn(true);

      ApiError error =
          assertApiError(
              () -> service.signup(signup("yamada", "yamada@example.com", "password123")),
              ErrorCode.ALREADY_REGISTERED);

      assertThat(error.errors())
          .containsExactly(
              new ApiError.FieldError("username", AuthService.USERNAME_TAKEN),
              new ApiError.FieldError("email", AuthService.EMAIL_TAKEN));
      verify(userMapper, never()).insert(any());
    }

    @Test
    void メールアドレスだけ使われていたら_メールアドレスだけを409で返す() {
      when(userMapper.existsByEmail("yamada@example.com")).thenReturn(true);

      ApiError error =
          assertApiError(
              () -> service.signup(signup("yamada", "YAMADA@example.com", "password123")),
              ErrorCode.ALREADY_REGISTERED);

      assertThat(error.errors())
          .containsExactly(new ApiError.FieldError("email", AuthService.EMAIL_TAKEN));
    }

    @Test
    void 重複チェックのあと同時に同じメールアドレスで登録されたら_DBが弾き409() {
      doThrow(new DuplicateKeyException("... violates unique constraint \"uq_users_email\""))
          .when(userMapper)
          .insert(any());

      ApiError error =
          assertApiError(
              () -> service.signup(signup("yamada", "yamada@example.com", "password123")),
              ErrorCode.ALREADY_REGISTERED);

      assertThat(error.errors())
          .containsExactly(new ApiError.FieldError("email", AuthService.EMAIL_TAKEN));
    }

    @Test
    void 重複チェックのあと同時に同じユーザー名で登録されたら_DBが弾き409() {
      doThrow(new DuplicateKeyException("... \"uq_users_username_lower\""))
          .when(userMapper)
          .insert(any());

      ApiError error =
          assertApiError(
              () -> service.signup(signup("yamada", "yamada@example.com", "password123")),
              ErrorCode.ALREADY_REGISTERED);

      assertThat(error.errors())
          .containsExactly(new ApiError.FieldError("username", AuthService.USERNAME_TAKEN));
    }
  }

  @Nested
  class ログイン {

    @Test
    void メールアドレスは小文字にして探し_パスワードが合えばトークンを返す() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("password123")));

      AuthResult result = service.login(new LoginRequest(" Yamada@Example.com ", "password123"));

      assertThat(result.accessToken()).isEqualTo("access-token");
      assertThat(result.user().username()).isEqualTo("yamada");
    }

    @Test
    void パスワードが違えば401() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("password123")));

      assertApiError(
          () -> service.login(new LoginRequest("yamada@example.com", "password124")),
          ErrorCode.INVALID_CREDENTIALS);
      verify(refreshTokenService, never()).issue(anyLong());
    }

    @Test
    void メールアドレスがなくても_パスワードが違うときと同じ401() {
      when(userMapper.findByEmail(anyString())).thenReturn(Optional.empty());

      assertApiError(
          () -> service.login(new LoginRequest("nobody@example.com", "password123")),
          ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void パスワードが72バイトを超えていれば_照合せずに401() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("あ".repeat(24))));

      assertApiError(
          () -> service.login(new LoginRequest("yamada@example.com", "あ".repeat(25))),
          ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 照合の空きがなければ503にし_トークンを発行しない() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("password123")));
      doThrow(new ApiException(ErrorCode.SERVER_BUSY)).when(passwordHashingGate).run(any());

      assertApiError(
          () -> service.login(new LoginRequest("yamada@example.com", "password123")),
          ErrorCode.SERVER_BUSY);
      verify(refreshTokenService, never()).issue(anyLong());
    }
  }

  /** ログインの試行回数の制限（Issue #159）。回数・期間の境界は LoginAttemptLimiterTest で確かめ、ここではログインの流れとのつながりを確かめる。 */
  @Nested
  class ログインの試行回数の制限 {

    @Test
    void 失敗が5回続いたら_正しいパスワードでも429にし_トークンを発行しない() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("password123")));
      failLogin("yamada@example.com", 5);

      assertApiError(
          () -> service.login(new LoginRequest("yamada@example.com", "password123")),
          ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
      verify(refreshTokenService, never()).issue(anyLong());
    }

    @Test
    void ロック中は_ユーザーを探さず_パスワードも照合しない() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("password123")));
      failLogin("yamada@example.com", 5);

      assertApiError(
          () -> service.login(new LoginRequest("yamada@example.com", "password123")),
          ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
      verify(userMapper, times(5)).findByEmail("yamada@example.com");
    }

    @Test
    void 登録されていないメールアドレスも同じように数える() {
      when(userMapper.findByEmail(anyString())).thenReturn(Optional.empty());
      failLogin("nobody@example.com", 5);

      assertApiError(
          () -> service.login(new LoginRequest("nobody@example.com", "password123")),
          ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
    }

    @Test
    void 大文字や前後の空白が違っても_同じメールアドレスとして数える() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("password123")));
      failLogin("Yamada@Example.com", 2);
      failLogin(" yamada@example.com ", 3);

      assertApiError(
          () -> service.login(new LoginRequest("yamada@example.com", "password123")),
          ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
    }

    @Test
    void 成功したら_それまでの失敗は数えない() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("password123")));
      failLogin("yamada@example.com", 4);
      service.login(new LoginRequest("yamada@example.com", "password123"));

      // 成功の前の4回を数えていたら、2回目の失敗から 429 になる
      failLogin("yamada@example.com", 4);
    }

    @Test
    void 照合の空きがなくて503になったときは_失敗として数えない() {
      when(userMapper.findByEmail("yamada@example.com"))
          .thenReturn(Optional.of(savedUser("password123")));
      doThrow(new ApiException(ErrorCode.SERVER_BUSY)).when(passwordHashingGate).run(any());
      for (int i = 0; i < 5; i++) {
        assertApiError(
            () -> service.login(new LoginRequest("yamada@example.com", "wrong-password")),
            ErrorCode.SERVER_BUSY);
      }
      doCallRealMethod().when(passwordHashingGate).run(any());

      // 503 の5回を失敗として数えていたら、ここで 429 になる
      assertThat(service.login(new LoginRequest("yamada@example.com", "password123")).accessToken())
          .isEqualTo("access-token");
    }

    /** パスワード違いで、指定した回数だけログインに失敗する（どれも 401 であること） */
    private void failLogin(String email, int times) {
      for (int i = 0; i < times; i++) {
        assertApiError(
            () -> service.login(new LoginRequest(email, "wrong-password")),
            ErrorCode.INVALID_CREDENTIALS);
      }
    }
  }

  @Nested
  class 再発行_ログアウト_ログイン中のユーザー {

    @Test
    void 再発行できたら_新しいアクセストークンと交換後のリフレッシュトークンを返す() {
      when(refreshTokenService.rotate("old"))
          .thenReturn(Optional.of(new RefreshTokenService.Rotation(7, "new-refresh-token")));
      when(userMapper.findById(7)).thenReturn(Optional.of(savedUser("password123")));

      AuthResult result = service.refresh("old");

      assertThat(result.accessToken()).isEqualTo("access-token");
      assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
    }

    @Test
    void リフレッシュトークンが無効なら401() {
      when(refreshTokenService.rotate("old")).thenReturn(Optional.empty());

      assertApiError(() -> service.refresh("old"), ErrorCode.SESSION_EXPIRED);
    }

    @Test
    void 再発行のときにユーザーが削除されていたら401() {
      when(refreshTokenService.rotate("old"))
          .thenReturn(Optional.of(new RefreshTokenService.Rotation(7, "new")));
      when(userMapper.findById(7)).thenReturn(Optional.empty());

      assertApiError(() -> service.refresh("old"), ErrorCode.SESSION_EXPIRED);
    }

    @Test
    void ログアウトはリフレッシュトークンを無効にする() {
      service.logout("token");

      verify(refreshTokenService).revoke("token");
    }

    @Test
    void ログイン中のユーザーはアイコンのURL付きで返し_削除されていれば401() {
      User user = savedUser("password123");
      user.setIconKey("icons/7.png");
      when(userMapper.findById(7)).thenReturn(Optional.of(user));
      when(imageStorage.urlOf("icons/7.png")).thenReturn("https://cdn/icons/7.png");
      when(userMapper.findById(8)).thenReturn(Optional.empty());

      assertThat(service.me(7).iconUrl()).isEqualTo("https://cdn/icons/7.png");
      assertApiError(() -> service.me(8), ErrorCode.UNAUTHENTICATED);
    }
  }
}
