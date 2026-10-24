package com.okimoto.sns.backend.logging;

import org.slf4j.MDC;

/**
 * 1つのリクエストの間に出すすべてのログに付ける項目（MDC）。docs/observability.md「3.3 必ず入れる項目」。
 *
 * <p>本番の JSON のログ（ECS）では、MDC の項目がそのままログの項目になる。リクエストが終わったら {@link RequestLoggingFilter} が消す
 */
public final class LogFields {

  /** リクエスト ID（{@link RequestLoggingFilter} が決める） */
  public static final String REQUEST_ID = "request_id";

  /** ログイン中のユーザーの ID（メールアドレスなどは出さない） */
  public static final String USER_ID = "user_id";

  /** エラーで返したときのエラーコード（{@code ErrorCode} の名前） */
  public static final String ERROR_CODE = "error_code";

  private LogFields() {}

  /** ログイン中のユーザーが分かったときに呼ぶ */
  public static void userId(long userId) {
    MDC.put(USER_ID, Long.toString(userId));
  }

  /** エラーの応答を返すときに呼ぶ（アクセスログに、どのエラーで返したかを残す） */
  public static void errorCode(String code) {
    MDC.put(ERROR_CODE, code);
  }
}
