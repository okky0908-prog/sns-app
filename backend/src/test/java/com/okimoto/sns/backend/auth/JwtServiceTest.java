package com.okimoto.sns.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

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
}
