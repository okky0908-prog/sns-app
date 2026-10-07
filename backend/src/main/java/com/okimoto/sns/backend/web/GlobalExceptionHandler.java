package com.okimoto.sns.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Controller とその先（Service・Mapper）で起きた例外を、docs/api.md のエラー形式に変換する。
 *
 * <ul>
 *   <li>業務上のエラー（{@link ApiException}）と入力チェックのエラーは、決まった code で返す
 *   <li>存在しない URL（404）・許可されないメソッド（405）など Spring が判定するエラーは、そのステータスに合う code で返す
 *   <li>それ以外（バグ・DB の障害など）は 500 / 503 にし、内部の情報は返さずにログにだけ残す
 * </ul>
 *
 * <p>JWT の確認など、Controller より手前（フィルター）で起きたエラーは {@link ApiErrorController} が同じ形式にする。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> handleApiException(ApiException e) {
    return respond(e.toApiError());
  }

  /** {@code @Valid} による入力チェックのエラー。項目ごとのメッセージを errors に入れる。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
    List<ApiError.FieldError> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
            .toList();
    return respond(ApiError.of(ErrorCode.VALIDATION_FAILED, errors));
  }

  /** URL のパラメータ（{@code @Min} など）の入力チェックのエラー。例：page に負の数を指定した */
  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException e) {
    List<ApiError.FieldError> errors =
        e.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            error ->
                                new ApiError.FieldError(
                                    result.getMethodParameter().getParameterName(),
                                    error.getDefaultMessage())))
            .toList();
    return respond(ApiError.of(ErrorCode.VALIDATION_FAILED, errors));
  }

  /** URL のパラメータの型が違うとき。例：投稿 ID に数字以外を指定した */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return respond(
        ApiError.of(
            ErrorCode.VALIDATION_FAILED,
            List.of(new ApiError.FieldError(e.getName(), "値の形式が正しくありません"))));
  }

  /** 必須の URL のパラメータがないとき。例：新しい投稿の件数で since を付けなかった */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException e) {
    return respond(
        ApiError.of(
            ErrorCode.VALIDATION_FAILED,
            List.of(new ApiError.FieldError(e.getParameterName(), "指定してください"))));
  }

  /** JSON の形が壊れているなど、リクエストを読めないとき。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    return respond(ApiError.of(ErrorCode.MALFORMED_REQUEST));
  }

  /** アップロードするファイルが上限を超えたとき（画像投稿の実装時に使う）。 */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException e) {
    return respond(ApiError.of(ErrorCode.PAYLOAD_TOO_LARGE));
  }

  /**
   * Controller の中で Spring Security の例外が投げられたとき（下の Exception で 500 にしないため）。
   * 通常はフィルターで判定されるので、ここには来ない。
   */
  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ApiError> handleAuthentication(AuthenticationException e) {
    return respond(ApiError.of(ErrorCode.UNAUTHENTICATED));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
    return respond(ApiError.of(ErrorCode.FORBIDDEN));
  }

  /**
   * ここまでのどれにも当たらない例外。
   *
   * <ul>
   *   <li>存在しない URL（404）・許可されないメソッド（405）・Content-Type 違い（415）など、Spring がステータスを決めている例外は、そのステータスで返す
   *   <li>DB につながらないときは 503（時間をおけば直る可能性がある）
   *   <li>それ以外はバグなど想定していないエラーなので 500
   * </ul>
   *
   * <p>500・503 は、原因を調べられるようリクエストとスタックトレースをログに残す。
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleOthers(Exception e, HttpServletRequest request) {
    if (e instanceof ErrorResponse errorResponse) {
      ErrorCode code = ErrorCode.fromStatus(errorResponse.getStatusCode());
      if (code.status().is4xxClientError()) {
        log.debug("{} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return respond(ApiError.of(code));
      }
    }
    if (isDatabaseUnavailable(e)) {
      log.error("DB に接続できません: {} {}", request.getMethod(), request.getRequestURI(), e);
      return respond(ApiError.of(ErrorCode.SERVICE_UNAVAILABLE));
    }
    log.error("想定していないエラー: {} {}", request.getMethod(), request.getRequestURI(), e);
    return respond(ApiError.of(ErrorCode.INTERNAL_ERROR));
  }

  /**
   * DB につながらないことが原因のエラーか。
   *
   * <p>DB が止まったときに投げられる例外は、どの時点で失敗したかで変わる（接続を取れない＝CannotCreateTransactionException、
   * 実行中に切れた＝DataAccessResourceFailureException、そのあとのロールバックも失敗した＝TransactionSystemException など）。
   * 例外の種類では決めず、原因をたどって「接続の失敗」があるかで判定する。
   */
  static boolean isDatabaseUnavailable(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataAccessResourceFailureException
          || cause instanceof TransientDataAccessResourceException
          || cause instanceof CannotCreateTransactionException
          // 接続プール（HikariCP）から時間内に接続を取れなかった
          || cause instanceof SQLTransientConnectionException
          // SQLSTATE の 08 は「接続の例外」（PostgreSQL が切断された など）
          || (cause instanceof SQLException sql
              && sql.getSQLState() != null
              && sql.getSQLState().startsWith("08"))) {
        return true;
      }
      // ロールバックの失敗は、元の失敗（ロールバックのきっかけになった例外）を別に持っているので、そちらも見る
      if (cause instanceof TransactionSystemException tse
          && tse.getApplicationException() != null
          && isDatabaseUnavailable(tse.getApplicationException())) {
        return true;
      }
    }
    return false;
  }

  private static ResponseEntity<ApiError> respond(ApiError body) {
    return ResponseEntity.status(body.status()).body(body);
  }
}
