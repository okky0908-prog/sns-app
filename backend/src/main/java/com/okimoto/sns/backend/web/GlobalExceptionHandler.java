package com.okimoto.sns.backend.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 例外を docs/api.md のエラー形式に変換する。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  static final String INVALID_INPUT = "入力内容に誤りがあります";

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> handleApiException(ApiException e) {
    return ResponseEntity.status(e.getStatus())
        .body(new ApiError(e.getStatus().value(), e.getMessage(), e.getErrors()));
  }

  /** {@code @Valid} による入力チェックのエラー。項目ごとのメッセージを errors に入れる。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
    List<ApiError.FieldError> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
            .toList();
    return ResponseEntity.badRequest()
        .body(new ApiError(HttpStatus.BAD_REQUEST.value(), INVALID_INPUT, errors));
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
    return ResponseEntity.badRequest()
        .body(new ApiError(HttpStatus.BAD_REQUEST.value(), INVALID_INPUT, errors));
  }

  /** URL のパラメータの型が違うとき。例：投稿 ID に数字以外を指定した */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.badRequest()
        .body(
            new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                INVALID_INPUT,
                List.of(new ApiError.FieldError(e.getName(), "値の形式が正しくありません"))));
  }

  /** 必須の URL のパラメータがないとき。例：新しい投稿の件数で since を付けなかった */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException e) {
    return ResponseEntity.badRequest()
        .body(
            new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                INVALID_INPUT,
                List.of(new ApiError.FieldError(e.getParameterName(), "指定してください"))));
  }

  /** JSON の形が壊れているなど、リクエストを読めないとき。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest()
        .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "リクエストの形式が正しくありません"));
  }
}
