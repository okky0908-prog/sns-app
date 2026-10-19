package com.okimoto.sns.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 期限切れのリフレッシュトークンの削除（docs/database.md「refresh_tokens」）。テストごとにロールバックするので、DB にデータは残らない。
 *
 * <p>「今」を固定するため、サービスは固定の時計を渡して作る。
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
class RefreshTokenCleanupTest {

  /** テストの「今」 */
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-19T04:00:00Z");

  @Autowired private RefreshTokenMapper refreshTokenMapper;
  @Autowired private RefreshTokenProperties properties;
  @Autowired private UserMapper userMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RefreshTokenCleanupJob cleanupJob;
  // 定期実行が有効でなければ一覧そのものがないので、あれば受け取る
  @Autowired private ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders;

  private RefreshTokenService service;
  private long userId;

  @BeforeEach
  void setUp() {
    service =
        new RefreshTokenService(
            refreshTokenMapper, properties, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    User user = new User("cleanup", "cleanup の名前", "cleanup@example.com", "dummy-hash", NOW);
    userMapper.insert(user);
    userId = user.getId();
  }

  @Test
  void 期限切れの行は無効にしたものも含めて削除し_期限内の行は残す() {
    insertToken("expired", NOW.minus(Duration.ofHours(1)), false);
    insertToken("expired-revoked", NOW.minus(Duration.ofHours(1)), true);
    insertToken("valid", NOW.plus(Duration.ofHours(1)), false);
    // 期限内の無効にした行は、使い回しの検知に使うので残す
    insertToken("valid-revoked", NOW.plus(Duration.ofHours(1)), true);

    int deleted = service.deleteExpired();

    assertThat(deleted).isEqualTo(2);
    assertThat(remainingHashes()).containsExactlyInAnyOrder("valid", "valid-revoked");
  }

  /** 再発行（RefreshTokenService#rotate）は、期限ちょうどのトークンを期限切れとして扱う。削除も同じ基準にする */
  @Test
  void 期限ちょうどの行も期限切れとして削除する() {
    insertToken("just-expired", NOW, false);
    insertToken("valid", NOW.plusNanos(1000), false);

    int deleted = service.deleteExpired();

    assertThat(deleted).isEqualTo(1);
    assertThat(remainingHashes()).containsExactly("valid");
  }

  /** 定期処理の本体。Spring が用意するもの（本物の時計）を使うので、行は実際の今の前後で作る */
  @Test
  void 定期処理を実行すると期限切れの行が削除される() {
    OffsetDateTime realNow = OffsetDateTime.now();
    insertToken("expired", realNow.minus(Duration.ofDays(1)), false);
    insertToken("valid", realNow.plus(Duration.ofDays(1)), false);

    cleanupJob.run();

    assertThat(remainingHashes()).containsExactly("valid");
  }

  /** 毎日 4:00（日本時間）に動くよう登録されている（app.refresh-token.cleanup-cron の既定値） */
  @Test
  void 定期処理は毎日4時に動くよう登録されている() {
    List<String> cronTasks =
        scheduledTaskHolders.stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .map(ScheduledTask::getTask)
            .filter(CronTask.class::isInstance)
            .map(task -> task.getRunnable() + " " + ((CronTask) task).getExpression())
            .toList();

    assertThat(cronTasks).contains(RefreshTokenCleanupJob.class.getName() + ".run 0 0 4 * * *");
  }

  /** トークンの行を入れる。テストで見分けやすいよう、ハッシュの代わりに名前を入れる（CHAR(64) なので後ろは空白で埋まる） */
  private void insertToken(String name, OffsetDateTime expiresAt, boolean revoked) {
    RefreshToken token =
        new RefreshToken(userId, name, expiresAt, expiresAt.minus(properties.expiration()));
    refreshTokenMapper.insert(token);
    if (revoked) {
      refreshTokenMapper.revoke(token.getId(), NOW.minus(Duration.ofDays(1)));
    }
  }

  private List<String> remainingHashes() {
    return jdbcTemplate.queryForList(
        "SELECT TRIM(token_hash) FROM refresh_tokens WHERE user_id = ?", String.class, userId);
  }
}
