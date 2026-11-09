package com.okimoto.sns.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * LoginAttemptLimiter の単体テスト（時計は進められる偽物）。
 *
 * <ul>
 *   <li>境界値分析：失敗の回数 4・5回、ロックの時間 15分の 1ナノ秒前・ちょうど、数える期間 15分の 1ナノ秒前・ちょうど
 *   <li>状態遷移：数えていない → 数えている → ロック → 解除（数え直し）、成功で数えていない状態に戻る
 *   <li>エラー推測：メールアドレスごとに分かれていること、ロック中の失敗の記録で時間が延びないこと、古い記録が残り続けないこと
 * </ul>
 */
class LoginAttemptLimiterTest {

  private static final String EMAIL = "taro@example.com";
  private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);
  private static final Duration ONE_NANO = Duration.ofNanos(1);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T00:00:00Z"));
  private final LoginAttemptLimiter limiter =
      new LoginAttemptLimiter(
          clock, new LoginAttemptProperties(5, FIFTEEN_MINUTES, FIFTEEN_MINUTES));

  @Test
  void 失敗が4回までなら_ロックしない() {
    failTimes(EMAIL, 4);

    assertNotLocked(EMAIL);
  }

  @Test
  void 失敗が5回になったら_ロックする() {
    failTimes(EMAIL, 5);

    assertLocked(EMAIL);
  }

  @Test
  void ロックは15分の1ナノ秒前まで続き_15分で解ける() {
    failTimes(EMAIL, 5);

    clock.advance(FIFTEEN_MINUTES.minus(ONE_NANO));
    assertLocked(EMAIL);

    clock.advance(ONE_NANO);
    assertNotLocked(EMAIL);
  }

  @Test
  void ロックが解けたら_回数は0から数え直す() {
    failTimes(EMAIL, 5);
    clock.advance(FIFTEEN_MINUTES);
    assertNotLocked(EMAIL);

    failTimes(EMAIL, 4);

    assertNotLocked(EMAIL);
  }

  @Test
  void ロック中に失敗を記録しても_ロックの時間は延びない() {
    failTimes(EMAIL, 5);
    clock.advance(Duration.ofMinutes(10));

    limiter.recordFailure(EMAIL);
    clock.advance(Duration.ofMinutes(5));

    assertNotLocked(EMAIL);
  }

  @Test
  void 最初の失敗から15分の1ナノ秒前までの失敗は_同じ期間として数える() {
    failTimes(EMAIL, 4);
    clock.advance(FIFTEEN_MINUTES.minus(ONE_NANO));

    limiter.recordFailure(EMAIL);

    assertLocked(EMAIL);
  }

  @Test
  void 最初の失敗から15分たったら_数え直す() {
    failTimes(EMAIL, 4);
    clock.advance(FIFTEEN_MINUTES);

    limiter.recordFailure(EMAIL);

    assertNotLocked(EMAIL);
  }

  @Test
  void 成功したら_それまでの失敗の回数を消す() {
    failTimes(EMAIL, 4);

    limiter.recordSuccess(EMAIL);
    limiter.recordFailure(EMAIL);

    assertNotLocked(EMAIL);
  }

  @Test
  void メールアドレスごとに数える() {
    failTimes(EMAIL, 5);

    assertNotLocked("hanako@example.com");
  }

  @Test
  void 期間が過ぎた記録は_掃除で消える() {
    failTimes(EMAIL, 5);
    failTimes("hanako@example.com", 1);
    clock.advance(FIFTEEN_MINUTES);

    limiter.removeExpired();

    assertThat(limiter.trackedCount()).isZero();
  }

  @Test
  void 期間の中の記録は_掃除しても残る() {
    failTimes(EMAIL, 5);
    failTimes("hanako@example.com", 1);
    clock.advance(FIFTEEN_MINUTES.minus(ONE_NANO));

    limiter.removeExpired();

    assertThat(limiter.trackedCount()).isEqualTo(2);
  }

  private void failTimes(String email, int times) {
    for (int i = 0; i < times; i++) {
      limiter.recordFailure(email);
    }
  }

  private void assertLocked(String email) {
    assertThatThrownBy(() -> limiter.checkNotLocked(email))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS));
  }

  private void assertNotLocked(String email) {
    assertThatCode(() -> limiter.checkNotLocked(email)).doesNotThrowAnyException();
  }

  /** テストの中で進められる時計 */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
