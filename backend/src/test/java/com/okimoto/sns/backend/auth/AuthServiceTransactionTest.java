package com.okimoto.sns.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.okimoto.sns.backend.support.TestcontainersConfiguration;
import com.okimoto.sns.backend.user.UserMapper;
import com.okimoto.sns.backend.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ログイン・新規登録で、BCrypt（約 80ms）の間に DB の接続を持ち続けないことのテスト（Issue #130）。
 *
 * <p>トランザクションの中で BCrypt を動かすと、その間ずっと接続プールの接続を1本使う。ログインが集中すると接続が足りなくなり、 ログインと関係のない API まで待たされる
 * （docs/performance-test-results.md のログイン集中）。
 *
 * <ul>
 *   <li>デシジョンテーブル：ログイン（成功・メールアドレスがない・パスワードが違う）、新規登録 → どれも BCrypt はトランザクションの外
 *   <li>エラー推測：トランザクションを外したことで、新規登録の「ユーザーの保存」と「リフレッシュトークンの発行」が別々に確定してしまわないか
 * </ul>
 *
 * <p>テストのクラスに @Transactional を付けると、テストの側でトランザクションが始まって確かめられないので付けない。作ったデータは後で消す。
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class AuthServiceTransactionTest {

  private static final String PASSWORD = "password123";

  @Autowired private AuthService authService;
  @Autowired private UserMapper userMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoSpyBean private PasswordEncoder passwordEncoder;
  @MockitoSpyBean private RefreshTokenService refreshTokenService;

  /** BCrypt が呼ばれたときに、トランザクションが始まっていたか */
  private final List<Boolean> transactionActiveDuringBcrypt = new ArrayList<>();

  @BeforeEach
  void recordTransactionDuringBcrypt() {
    Answer<Object> record =
        invocation -> {
          transactionActiveDuringBcrypt.add(
              TransactionSynchronizationManager.isActualTransactionActive());
          return invocation.callRealMethod();
        };
    doAnswer(record).when(passwordEncoder).matches(any(), any());
    doAnswer(record).when(passwordEncoder).encode(any());
  }

  @AfterEach
  void deleteUsers() {
    // refresh_tokens は ON DELETE CASCADE で一緒に消える
    jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'txtest%'");
  }

  private void signup(String username) {
    authService.signup(
        new SignupRequest(username, "トランザクション", username + "@example.com", PASSWORD));
    transactionActiveDuringBcrypt.clear();
  }

  @Test
  void ログインに成功するとき_BCryptの照合はトランザクションの外で行う() {
    signup("txtest01");

    authService.login(new LoginRequest("txtest01@example.com", PASSWORD));

    assertThat(transactionActiveDuringBcrypt).containsExactly(false);
  }

  @Test
  void メールアドレスがないとき_ダミーのハッシュとの照合もトランザクションの外で行う() {
    assertThatThrownBy(
            () -> authService.login(new LoginRequest("txtest-none@example.com", PASSWORD)))
        .isInstanceOf(ApiException.class);

    assertThat(transactionActiveDuringBcrypt).containsExactly(false);
  }

  @Test
  void パスワードが違うとき_BCryptの照合はトランザクションの外で行う() {
    signup("txtest02");

    assertThatThrownBy(
            () -> authService.login(new LoginRequest("txtest02@example.com", "wrong-password")))
        .isInstanceOf(ApiException.class);

    assertThat(transactionActiveDuringBcrypt).containsExactly(false);
  }

  @Test
  void 新規登録のとき_パスワードのハッシュ化はトランザクションの外で行う() {
    authService.signup(new SignupRequest("txtest03", "トランザクション", "txtest03@example.com", PASSWORD));

    assertThat(transactionActiveDuringBcrypt).containsExactly(false);
  }

  @Test
  void 新規登録でリフレッシュトークンの発行に失敗したとき_ユーザーも保存しない() {
    doThrow(new IllegalStateException("発行に失敗")).when(refreshTokenService).issue(anyLong());

    assertThatThrownBy(
            () ->
                authService.signup(
                    new SignupRequest("txtest04", "トランザクション", "txtest04@example.com", PASSWORD)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(userMapper.findByEmail("txtest04@example.com")).isEmpty();
  }
}
