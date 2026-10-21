package com.okimoto.sns.backend.support;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.auth.SecurityConfig;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Controller のテストに付ける注釈（docs/test-plan.md「テストの層」の Controller）。
 *
 * <ul>
 *   <li>{@code @WebMvcTest}：指定した Controller と、例外ハンドラー・JSON の変換だけを読み込む（DB・Service は読み込まない）。Service
 *       は各テストで {@code @MockitoBean} の偽物にする
 *   <li>認証の設定（SecurityConfig）は本物を使う。「ログインなしなら 401」「新規登録はログインなしで呼べる」という決まりそのものを確かめるため
 *   <li>JwtService は偽物にする。{@link TestTokens} で「user-1 というトークンならユーザー1」と決めて使う
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest
@Import(SecurityConfig.class)
@MockitoBean(types = JwtService.class)
@ActiveProfiles("test")
public @interface ControllerTest {

  /** テストする Controller */
  @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
  Class<?>[] value();
}
