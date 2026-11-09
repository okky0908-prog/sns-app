package com.okimoto.sns.backend.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ログインの試行回数の制限（application.yml の app.login-attempt。docs/feature-specs/01_auth.md「ログインの試行回数の制限」）。
 *
 * @param maxFailures この回数だけ続けて失敗したらロックする（5回）
 * @param window 失敗を数える期間（15分）。最初の失敗からこの時間がたったら、数え直す
 * @param lockDuration ロックしている時間（15分）
 */
@ConfigurationProperties("app.login-attempt")
public record LoginAttemptProperties(int maxFailures, Duration window, Duration lockDuration) {}
