package com.okimoto.sns.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 期限切れのリフレッシュトークンを定期的に削除する。 */
@Component
public class RefreshTokenCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

  private final RefreshTokenService refreshTokenService;

  public RefreshTokenCleanupJob(RefreshTokenService refreshTokenService) {
    this.refreshTokenService = refreshTokenService;
  }

  /** 毎日 4:00（日本時間。利用者の少ない時間帯）に実行する。時刻は app.refresh-token.cleanup-cron で変えられる */
  @Scheduled(cron = "${app.refresh-token.cleanup-cron}", zone = "Asia/Tokyo")
  public void run() {
    int deleted = refreshTokenService.deleteExpired();
    log.info("期限切れのリフレッシュトークンを {} 件削除しました", deleted);
  }
}
