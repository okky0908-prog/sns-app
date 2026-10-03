package com.okimoto.sns.backend.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/** 業務上のエラー。GlobalExceptionHandler が status と errors をそのままエラーレスポンスにする。 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final List<ApiError.FieldError> errors;

  public ApiException(HttpStatus status, String message, List<ApiError.FieldError> errors) {
    super(message);
    this.status = status;
    this.errors = List.copyOf(errors);
  }

  public ApiException(HttpStatus status, String message) {
    this(status, message, List.of());
  }

  /** 項目ごとの入力エラー（400）。 */
  public static ApiException badRequest(List<ApiError.FieldError> errors) {
    return new ApiException(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.INVALID_INPUT, errors);
  }

  /** ユーザー名・メールアドレスの重複など（409）。 */
  public static ApiException conflict(List<ApiError.FieldError> errors) {
    return new ApiException(HttpStatus.CONFLICT, "すでに登録されています", errors);
  }

  public HttpStatus getStatus() {
    return status;
  }

  public List<ApiError.FieldError> getErrors() {
    return errors;
  }
}
