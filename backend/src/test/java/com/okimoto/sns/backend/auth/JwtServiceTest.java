package com.okimoto.sns.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
    assertThatThrownBy(() -> serviceAt(NOW, "short-secret")).hasMessageContaining("短すぎます");
  }
}
