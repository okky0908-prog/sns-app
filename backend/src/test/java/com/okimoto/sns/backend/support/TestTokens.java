package com.okimoto.sns.backend.support;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.auth.JwtService;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * Controller のテストで使うアクセストークン。偽物の JwtService に「user-{ID}
 * というトークンならそのユーザー」と教える。それ以外のトークンは無効（期限切れ・改ざんなど）として扱う。
 */
public final class TestTokens {

  private TestTokens() {}

  /** 偽物の JwtService に、トークンの読み方を教える（各テストの準備で呼ぶ） */
  public static void teach(JwtService jwtService) {
    when(jwtService.parseUserId(anyString()))
        .thenAnswer(
            invocation -> {
              String token = invocation.getArgument(0);
              return token.matches("user-\\d+")
                  ? Optional.of(Long.parseLong(token.substring("user-".length())))
                  : Optional.empty();
            });
  }

  /** ログイン済み（ユーザー userId）のリクエストにする（通常のリクエストにも multipart にも使える） */
  public static <B extends AbstractMockHttpServletRequestBuilder<B>> B as(long userId, B request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer user-" + userId);
  }

  /** 無効なトークン（期限切れ・改ざんなど）を付ける */
  public static <B extends AbstractMockHttpServletRequestBuilder<B>> B withInvalidToken(B request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-tampered");
  }
}
