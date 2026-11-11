package com.okimoto.sns.backend.auth;

import static com.okimoto.sns.backend.support.ApiErrors.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * PasswordHashingGate の単体テスト（Issue #176）。
 *
 * <ul>
 *   <li>境界値分析：同時に動いている数が上限の 1 つ手前・ちょうど
 *   <li>状態遷移：空きあり → 上限まで使用中（待つ・時間切れで 503）→ 終わって空く
 *   <li>エラー推測：処理が例外で終わっても空きが戻ること
 * </ul>
 */
class PasswordHashingGateTest {

  private static final Duration SHORT_WAIT = Duration.ofMillis(50);

  private final ExecutorService executor = Executors.newCachedThreadPool();

  /** occupy で動かしている処理を終わらせる合図 */
  private final CountDownLatch finish = new CountDownLatch(1);

  @AfterEach
  void tearDown() throws InterruptedException {
    finish.countDown();
    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void 空きがあれば_そのまま実行して結果を返す() {
    PasswordHashingGate gate =
        new PasswordHashingGate(new PasswordHashingProperties(2, SHORT_WAIT));

    assertThat(gate.run(() -> "結果")).isEqualTo("結果");
  }

  @Test
  void 上限まで動いていれば_待つ時間を過ぎたところで503にし_処理を実行しない() throws Exception {
    PasswordHashingGate gate =
        new PasswordHashingGate(new PasswordHashingProperties(2, SHORT_WAIT));
    occupy(gate, 2);

    boolean[] ran = {false};
    long start = System.nanoTime();
    assertApiError(
        () ->
            gate.run(() -> {
              ran[0] = true;
              return "結果";
            }),
        ErrorCode.SERVER_BUSY);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(SHORT_WAIT);
    assertThat(ran[0]).isFalse();
  }

  @Test
  void 上限の1つ手前までなら_待たずに実行する() throws Exception {
    PasswordHashingGate gate =
        new PasswordHashingGate(new PasswordHashingProperties(2, Duration.ofSeconds(5)));
    occupy(gate, 1);

    long start = System.nanoTime();
    assertThat(gate.run(() -> "結果")).isEqualTo("結果");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void 待っている間に空けば_実行する() throws Exception {
    PasswordHashingGate gate =
        new PasswordHashingGate(new PasswordHashingProperties(1, Duration.ofSeconds(5)));
    occupy(gate, 1);

    executor.submit(() -> {
      Thread.sleep(100);
      finish.countDown();
      return null;
    });

    assertThat(gate.run(() -> "結果")).isEqualTo("結果");
  }

  @Test
  void 処理が例外で終わっても_空きを戻す() {
    PasswordHashingGate gate =
        new PasswordHashingGate(new PasswordHashingProperties(1, SHORT_WAIT));

    assertThatThrownBy(() ->
            gate.run(() -> {
              throw new IllegalStateException("失敗");
            }))
        .hasMessage("失敗");

    assertThat(gate.run(() -> "結果")).isEqualTo("結果");
  }

  /** gate の中で、finish の合図まで終わらない処理を count 件動かし、すべてが中に入るまで待つ */
  private void occupy(PasswordHashingGate gate, int count) throws InterruptedException {
    CountDownLatch entered = new CountDownLatch(count);
    for (int i = 0; i < count; i++) {
      executor.submit(() ->
          gate.run(() -> {
            entered.countDown();
            try {
              return finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
            }
          }));
    }
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
  }
}
