package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * パスワードのハッシュ化・照合（BCrypt）を同時に動かす数を絞る（Issue #176）。
 *
 * <p>BCrypt は CPU だけを使う重い処理（0.5 vCPU のタスクで 1 件あたり約 0.15 秒分）。ログインが集中して BCrypt が CPU を取り合うと、ログイン以外の
 * API まで CPU の順番を待たされて止まる（docs/performance-test-results.md「AWS での測定」）。同時に動かす数を絞り、空きを {@code
 * maxWait} だけ待っても空かなければ 503（{@link ErrorCode#SERVER_BUSY}）ですぐ返して、ほかの API の CPU を残す。
 */
@Component
public class PasswordHashingGate {

  private final Semaphore permits;
  private final Duration maxWait;

  public PasswordHashingGate(PasswordHashingProperties properties) {
    // 公平（待った順）にする。後から来たものに追い越され続けて、時間切れになることがないように
    this.permits = new Semaphore(properties.maxConcurrent(), true);
    this.maxWait = properties.maxWait();
  }

  /** 空きを待ってから task を実行する。待つ時間を過ぎても空かなければ、task を実行せずに 503 にする */
  public <T> T run(Supplier<T> task) {
    if (!tryAcquire()) {
      throw new ApiException(ErrorCode.SERVER_BUSY);
    }
    try {
      return task.get();
    } finally {
      permits.release();
    }
  }

  private boolean tryAcquire() {
    try {
      return permits.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
