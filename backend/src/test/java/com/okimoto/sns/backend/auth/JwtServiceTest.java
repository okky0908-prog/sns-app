package com.okimoto.sns.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.okimoto.sns.backend.logging.AuditLog;
import com.okimoto.sns.backend.support.LogCapture;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class JwtServiceTest {

  @RegisterExtension final LogCapture logs = new LogCapture();

  private static final String SECRET = "test-secret-key-for-unit-tests-only-0123456789";
  private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

  private static JwtService serviceAt(Instant instant, String secret) {
    return new JwtService(
        new JwtProperties(secret, Duration.ofHours(24)), Clock.fixed(instant, ZoneOffset.UTC));
  }

  @Test
  void 発行したトークンからユーザーIDを取り出せる() {
    JwtService service = serviceAt(NOW, SECRET);
    assertThat(service.parseUserId(service.issue(42))).contains(42L);
  }

  @Test
  void 有効期限の24時間を過ぎたトークンは無効() {
    String token = serviceAt(NOW, SECRET).issue(42);
    assertThat(serviceAt(NOW.plus(Duration.ofHours(23)), SECRET).parseUserId(token)).contains(42L);
    assertThat(serviceAt(NOW.plus(Duration.ofHours(25)), SECRET).parseUserId(token)).isEmpty();
  }

  @Test
  void 別の鍵で署名されたトークンは無効() {
    String token = serviceAt(NOW, "another-secret-key-for-unit-tests-0123456789").issue(42);
    assertThat(serviceAt(NOW, SECRET).parseUserId(token)).isEmpty();
  }

  @Test
  void 改ざんされたトークンや形式の違う文字列は無効() {
    JwtService service = serviceAt(NOW, SECRET);
    String token = service.issue(42);
    String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
    assertThat(service.parseUserId(tampered)).isEmpty();
    assertThat(service.parseUserId("not-a-jwt")).isEmpty();
    assertThat(service.parseUserId("")).isEmpty();
  }

  @Test
  void 署名鍵が未設定または32バイト未満なら起動時にエラー() {
    assertThatThrownBy(() -> serviceAt(NOW, "")).hasMessageContaining("JWT_SECRET");
    // 設定ファイルに署名鍵の項目そのものがないとき
    assertThatThrownBy(() -> serviceAt(NOW, null)).hasMessageContaining("JWT_SECRET");
    assertThatThrownBy(() -> serviceAt(NOW, "short-secret")).hasMessageContaining("短すぎます");
  }

  // ===== 境界値・エラー推測（docs/test-plan.md 5.2） =====

  @Test
  void 有効期限の1秒前までは有効で_1秒後からは無効() {
    String token = serviceAt(NOW, SECRET).issue(42);
    Instant expiresAt = NOW.plus(Duration.ofHours(24));

    assertThat(serviceAt(expiresAt.minusSeconds(1), SECRET).parseUserId(token)).contains(42L);
    assertThat(serviceAt(expiresAt.plusSeconds(1), SECRET).parseUserId(token)).isEmpty();
  }

  @Test
  void 署名鍵はちょうど32バイトなら使え_31バイトならエラー() {
    assertThat(serviceAt(NOW, "a".repeat(32)).issue(1)).isNotBlank();
    assertThatThrownBy(() -> serviceAt(NOW, "a".repeat(31))).hasMessageContaining("31バイト");
    // バイトで数える（日本語は1文字3バイト）。11文字＝33バイトなら使える
    assertThat(serviceAt(NOW, "あ".repeat(11)).issue(1)).isNotBlank();
  }

  @Test
  void 正しい鍵で署名されていても_ユーザーIDが数字でなければ無効() {
    String token =
        Jwts.builder()
            .subject("not-a-number")
            .expiration(Date.from(NOW.plus(Duration.ofHours(1))))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

    assertThat(serviceAt(NOW, SECRET).parseUserId(token)).isEmpty();
  }

  @Test
  void ユーザーIDのない署名済みトークンは無効() {
    String token =
        Jwts.builder()
            .expiration(Date.from(NOW.plus(Duration.ofHours(1))))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

    assertThat(serviceAt(NOW, SECRET).parseUserId(token)).isEmpty();
  }

  // ===== 検証に失敗したときのログ（デシジョンテーブル：トークンの状態 × ログ） =====

  /** 検証の失敗を記録した警告 */
  private List<ILoggingEvent> invalidTokenLogs() {
    return logs.from(AuditLog.class).stream()
        .filter(e -> "invalid_token".equals(LogCapture.keyValues(e).get("event.action")))
        .toList();
  }

  private void assertInvalidTokenLogged(String reason, String token) {
    assertThat(invalidTokenLogs()).hasSize(1);
    ILoggingEvent log = invalidTokenLogs().getFirst();
    assertThat(log.getLevel()).isEqualTo(Level.WARN);
    assertThat(LogCapture.keyValues(log)).containsEntry("reason", reason);
    // トークンそのものは出さない（ほかの人に使われないように）
    if (!token.isEmpty()) {
      assertThat(logs.allText()).doesNotContain(token);
    }
  }

  @Test
  void 正しいトークンは記録しない() {
    JwtService service = serviceAt(NOW, SECRET);

    service.parseUserId(service.issue(42));

    assertThat(logs.events()).isEmpty();
  }

  @Test
  void 期限切れのトークンは記録しない() {
    // アクセストークンは短い期限で必ず切れ、画面が自動で再発行する。毎日たくさん起きるふつうのことなので、警告にしない
    String token = serviceAt(NOW, SECRET).issue(42);

    serviceAt(NOW.plus(Duration.ofHours(25)), SECRET).parseUserId(token);

    assertThat(invalidTokenLogs()).isEmpty();
  }

  @Test
  void 別の鍵で署名されたトークンは_署名が合わないとして警告で記録する() {
    String token = serviceAt(NOW, "another-secret-key-for-unit-tests-0123456789").issue(42);

    serviceAt(NOW, SECRET).parseUserId(token);

    assertInvalidTokenLogged("signature_invalid", token);
  }

  @Test
  void 改ざんされたトークンは_署名が合わないとして警告で記録する() {
    JwtService service = serviceAt(NOW, SECRET);
    String token = service.issue(42);
    String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

    service.parseUserId(tampered);

    assertInvalidTokenLogged("signature_invalid", tampered);
  }

  @Test
  void JWTの形でない文字列は_形がおかしいとして警告で記録する() {
    serviceAt(NOW, SECRET).parseUserId("not-a-jwt");

    assertInvalidTokenLogged("malformed", "not-a-jwt");
  }

  @Test
  void 空のトークンは_形がおかしいとして警告で記録する() {
    serviceAt(NOW, SECRET).parseUserId("");

    assertInvalidTokenLogged("malformed", "");
  }
}
