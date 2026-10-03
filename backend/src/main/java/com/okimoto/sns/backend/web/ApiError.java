package com.okimoto.sns.backend.web;

import java.util.List;

/**
 * エラーレスポンスの共通形式（docs/api.md「エラーレスポンス」）。
 *
 * <pre>
 * { "status": 400, "message": "入力内容に誤りがあります", "errors": [{ "field": "...", "message": "..." }] }
 * </pre>
 */
public record ApiError(int status, String message, List<FieldError> errors) {

  public record FieldError(String field, String message) {}

  public static ApiError of(int status, String message) {
    return new ApiError(status, message, List.of());
  }
}
