package com.okimoto.sns.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Service の単体テストで、業務上のエラー（ApiException）の種類と項目ごとのエラーを確かめる。 */
public final class ApiErrors {

  private ApiErrors() {}

  /** call が code の ApiException を投げることを確かめ、そのエラー（レスポンスの形）を返す */
  public static ApiError assertApiError(ThrowingCallable call, ErrorCode code) {
    ApiException thrown = catchThrowableOfType(ApiException.class, call);
    assertThat(thrown).as("ApiException（%s）が投げられること", code).isNotNull();
    assertThat(thrown.getCode()).isEqualTo(code);
    return thrown.toApiError();
  }

  /** call が入力エラー（400 VALIDATION_FAILED）を投げ、その項目と文言が field・message であることを確かめる */
  public static void assertFieldError(ThrowingCallable call, String field, String message) {
    ApiError error = assertApiError(call, ErrorCode.VALIDATION_FAILED);
    assertThat(error.errors()).containsExactly(new ApiError.FieldError(field, message));
  }
}
