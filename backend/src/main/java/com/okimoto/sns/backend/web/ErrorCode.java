package com.okimoto.sns.backend.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * エラーの種類（docs/api.md「エラーレスポンス」）。レスポンスの {@code code} にこの名前がそのまま入る。
 *
 * <p>画面側は文言（message）ではなく code でエラーを見分ける（文言を変えても画面の動きが変わらないように）。 エラーの種類を増やすときは、ここに追加し、docs/api.md
 * とフロントの {@code ApiErrorCode} にも同じ名前を追加する。 一度使い始めた名前は変えない。
 */
public enum ErrorCode {

  // ===== 400 =====
  /** 入力チェックのエラー。項目ごとの内容は errors に入る */
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "入力内容に誤りがあります"),
  /** JSON が壊れている・必要なパラメータの形が違うなど、リクエストそのものを読めない */
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "リクエストの形式が正しくありません"),

  // ===== 401 =====
  /** アクセストークンがない・正しくない・期限切れ。画面側は再発行（A-04）して1回だけやり直す */
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "ログインが必要です"),
  /** ログインでメールアドレスまたはパスワードが違う（どちらが違うかは教えない） */
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "メールアドレスまたはパスワードが正しくありません"),
  /** リフレッシュトークンがない・無効・期限切れ。もう一度ログインしてもらう */
  SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "ログインの有効期限が切れました。もう一度ログインしてください"),

  // ===== 403 =====
  /** 他人の投稿を編集・削除しようとしたなど、権限がない */
  FORBIDDEN(HttpStatus.FORBIDDEN, "この操作は実行できません"),

  // ===== 404 =====
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "この投稿は見つかりません"),
  /** 存在しない URL（API） */
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "指定された URL は存在しません"),

  // ===== 405 / 409 / 413 / 415 =====
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "この URL では、この操作はできません"),
  /** ユーザー名・メールアドレスの重複など。どの項目かは errors に入る */
  ALREADY_REGISTERED(HttpStatus.CONFLICT, "すでに登録されています"),
  PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "送信するデータが大きすぎます"),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "リクエストの形式（Content-Type）に対応していません"),

  // ===== 500 / 503 =====
  /** 想定していないエラー（バグなど）。内部の情報は利用者に出さず、ログにだけ残す */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "サーバーでエラーが発生しました。時間をおいてもう一度お試しください"),
  /** DB につながらないなど、一時的に処理できない */
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ただいまサービスを利用できません。時間をおいてもう一度お試しください");

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus status() {
    return status;
  }

  /** 利用者向けの標準の文言 */
  public String message() {
    return message;
  }

  /**
   * Spring が決めたステータス（404・405 など）に合う種類。個別の種類がないものは 4xx なら MALFORMED_REQUEST、5xx なら INTERNAL_ERROR
   */
  public static ErrorCode fromStatus(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> MALFORMED_REQUEST;
      case 401 -> UNAUTHENTICATED;
      case 403 -> FORBIDDEN;
      case 404 -> RESOURCE_NOT_FOUND;
      case 405 -> METHOD_NOT_ALLOWED;
      case 413 -> PAYLOAD_TOO_LARGE;
      case 415 -> UNSUPPORTED_MEDIA_TYPE;
      case 503 -> SERVICE_UNAVAILABLE;
      default -> status.is4xxClientError() ? MALFORMED_REQUEST : INTERNAL_ERROR;
    };
  }
}
