package com.okimoto.sns.backend.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 監査ログ（誰が何をしたか）とセキュリティのログ（攻撃・不正の疑い）。docs/observability.md「3.1 ログの種類」。
 *
 * <ul>
 *   <li>どの操作かを {@code event.action} に入れる（数えたり、アラートの条件にしたりするため）
 *   <li>誰がしたかは MDC のユーザー ID（{@link LogFields#USER_ID}）で残す。新規登録・ログイン・ログアウトのように、
 *       アクセストークンなしで呼ばれる操作では、ここでユーザー ID を入れる（そのリクエストのアクセスログにも入る）
 *   <li>本文・メールアドレス・トークンは出さない（docs/observability.md「3.5 出さない情報」）。対象は ID で残す
 * </ul>
 */
public final class AuditLog {

  private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
  private static final String ACTION = "event.action";

  private AuditLog() {}

  public static void signedUp(long userId) {
    LogFields.userId(userId);
    log.atInfo().addKeyValue(ACTION, "signup").log("新規登録しました");
  }

  public static void loggedIn(long userId) {
    LogFields.userId(userId);
    log.atInfo().addKeyValue(ACTION, "login").log("ログインしました");
  }

  /** パスワード違い・登録されていないメールアドレス。急に増えたら総当たり攻撃の疑い（docs/observability.md「5.2」の A6） */
  public static void loginFailed(String email) {
    String masked = maskEmail(email);
    log.atWarn()
        .addKeyValue(ACTION, "login_failed")
        .addKeyValue("user.email", masked)
        .log("ログインに失敗しました: {}", masked);
  }

  public static void loggedOut(long userId) {
    LogFields.userId(userId);
    log.atInfo().addKeyValue(ACTION, "logout").log("ログアウトしました");
  }

  /** 使用済みのリフレッシュトークンが使われた（盗まれた疑い）。docs/observability.md「5.2」の A7 */
  public static void refreshTokenReused(long userId) {
    LogFields.userId(userId);
    log.atWarn()
        .addKeyValue(ACTION, "refresh_token_reuse")
        .log("使用済みのリフレッシュトークンが使われたので、このユーザーのトークンをすべて無効にしました（盗まれた疑い）");
  }

  public static void postDeleted(long postId) {
    log.atInfo().addKeyValue(ACTION, "post_delete").addKeyValue("post.id", postId).log("投稿を削除しました");
  }

  public static void commentDeleted(long commentId) {
    log.atInfo()
        .addKeyValue(ACTION, "comment_delete")
        .addKeyValue("comment.id", commentId)
        .log("コメントを削除しました");
  }

  public static void profileUpdated() {
    log.atInfo().addKeyValue(ACTION, "profile_update").log("プロフィールを変更しました");
  }

  /** 権限のない操作（403）。画面からは起きない操作なので、続くなら不正の疑い */
  public static void forbidden() {
    log.atWarn().addKeyValue(ACTION, "forbidden").log("権限のない操作が行われました");
  }

  /** メールアドレスの一部を隠す。先頭の1文字と @ 以降だけを残す（a***@example.com）。形がおかしければすべて隠す */
  static String maskEmail(String email) {
    int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}
