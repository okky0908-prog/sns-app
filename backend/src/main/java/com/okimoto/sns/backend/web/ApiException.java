package com.okimoto.sns.backend.web;

import java.util.List;

/** 業務上のエラー。GlobalExceptionHandler が code と errors をそのままエラーレスポンスにする。 */
public class ApiException extends RuntimeException {

  private final ErrorCode code;
  private final List<ApiError.FieldError> errors;

  public ApiException(ErrorCode code, List<ApiError.FieldError> errors) {
    super(code.message());
    this.code = code;
    this.errors = List.copyOf(errors);
  }

  public ApiException(ErrorCode code) {
    this(code, List.of());
  }

  /** 項目ごとの入力エラー（400）。 */
  public static ApiException badRequest(List<ApiError.FieldError> errors) {
    return new ApiException(ErrorCode.VALIDATION_FAILED, errors);
  }

  /** ユーザー名・メールアドレスの重複など（409）。 */
  public static ApiException conflict(List<ApiError.FieldError> errors) {
    return new ApiException(ErrorCode.ALREADY_REGISTERED, errors);
  }

  public ErrorCode getCode() {
    return code;
  }

  public ApiError toApiError() {
    return ApiError.of(code, errors);
  }
}
