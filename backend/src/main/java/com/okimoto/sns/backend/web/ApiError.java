package com.okimoto.sns.backend.web;

import java.util.List;

/**
 * エラーレスポンスの共通形式（docs/api.md「エラーレスポンス」）。
 *
 * <pre>
 * { "status": 400, "code": "VALIDATION_FAILED", "message": "入力内容に誤りがあります",
 *   "errors": [{ "field": "...", "message": "..." }] }
 * </pre>
 *
 * @param code エラーの種類（{@link ErrorCode} の名前）。画面側はこれで見分ける
 * @param message 利用者にそのまま見せてよい文言
 * @param errors 項目ごとの入力エラー。なければ空
 */
public record ApiError(int status, String code, String message, List<FieldError> errors) {

  public record FieldError(String field, String message) {}

  public static ApiError of(ErrorCode code) {
    return of(code, List.of());
  }

  public static ApiError of(ErrorCode code, List<FieldError> errors) {
    return new ApiError(code.status().value(), code.name(), code.message(), errors);
  }
}
