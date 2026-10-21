package com.okimoto.sns.backend.auth;

import static com.okimoto.sns.backend.support.TestTokens.as;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.support.ControllerTest;
import com.okimoto.sns.backend.support.TestTokens;
import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * AuthController（A-01〜A-05）。テスト計画書 5.3 の基本ケース（Service は偽物）。
 *
 * <ul>
 *   <li>境界値分析：ユーザー名 3・4・15・16文字、表示名 0・1・50・51文字、パスワード 7・8・72・73文字、メールアドレスの形式と 255文字
 *   <li>デシジョンテーブル（認証）：新規登録・ログイン・再発行・ログアウトはログインなしで呼べる。ログイン中のユーザー（A-03）はログインが必要
 *   <li>リフレッシュトークンの Cookie の属性（HttpOnly・SameSite=Strict・Path=/api/auth・期限14日）。ログアウトでは期限0で消す
 * </ul>
 */
@ControllerTest(AuthController.class)
// Cookie の期限などの設定（Controller だけを読み込むテストでは、自動では用意されない）。値はテスト用の設定ファイルから読む
@EnableConfigurationProperties(RefreshTokenProperties.class)
class AuthControllerWebMvcTest {

  private static final UserResponse USER = new UserResponse(7, "yamada", "山田太郎", null);
  private static final AuthResult RESULT = new AuthResult("access-token", "refresh-token", USER);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtService jwtService;
  @MockitoBean private AuthService authService;

  @BeforeEach
  void setUp() {
    TestTokens.teach(jwtService);
  }

  private ResultActions postJson(String path, Map<String, String> body) throws Exception {
    return mockMvc.perform(
        post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  private static Map<String, String> signup(String field, String value) {
    Map<String, String> body =
        new HashMap<>(
            Map.of(
                "username", "yamada",
                "displayName", "山田太郎",
                "email", "yamada@example.com",
                "password", "password123"));
    if (value == null) {
      body.remove(field);
    } else {
      body.put(field, value);
    }
    return body;
  }

  @Nested
  class 新規登録 {

    @Test
    void ログインなしで呼べ_201でアクセストークンを返し_リフレッシュトークンはCookieで返す() throws Exception {
      when(authService.signup(any())).thenReturn(RESULT);

      postJson("/api/auth/signup", signup("username", "yamada"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.accessToken").value("access-token"))
          .andExpect(jsonPath("$.user.username").value("yamada"))
          // リフレッシュトークンは JavaScript から読めないよう、JSON には入れない
          .andExpect(jsonPath("$.refreshToken").doesNotExist())
          .andExpect(
              header()
                  .string(
                      HttpHeaders.SET_COOKIE,
                      allOf(
                          containsString("refresh_token=refresh-token"),
                          containsString("Path=/api/auth"),
                          containsString("Max-Age=1209600"), // 14日
                          containsString("HttpOnly"),
                          containsString("SameSite=Strict"),
                          not(containsString("Secure"))))); // テストの設定は HTTP なので Secure を付けない

      verify(authService)
          .signup(
              argThat(
                  r ->
                      r.username().equals("yamada")
                          && r.email().equals("yamada@example.com")
                          && r.password().equals("password123")));
    }

    @ParameterizedTest(name = "{0}「{1}」は受け付ける")
    @CsvSource({
      "username, abcd", // 4文字
      "username, a23456789012345", // 15文字
      "username, a_B_9",
      "displayName, あ", // 1文字
      "password, 12345678", // 8文字
      "email, a@b.c"
    })
    void 境界の内側は受け付ける(String field, String value) throws Exception {
      when(authService.signup(any())).thenReturn(RESULT);

      postJson("/api/auth/signup", signup(field, value)).andExpect(status().isCreated());
    }

    @Test
    void 表示名は絵文字50個まで_パスワードは72文字まで受け付ける() throws Exception {
      when(authService.signup(any())).thenReturn(RESULT);

      postJson("/api/auth/signup", signup("displayName", "🎉".repeat(50)))
          .andExpect(status().isCreated());
      postJson("/api/auth/signup", signup("password", "a".repeat(72)))
          .andExpect(status().isCreated());
    }

    @ParameterizedTest(name = "{0}「{1}」は 400「{2}」")
    @CsvSource(
        delimiter = '|',
        nullValues = "<なし>",
        value = {
          "username    | abc                | ユーザー名は半角英数字と_で4〜15文字で入力してください",
          "username    | a234567890123456   | ユーザー名は半角英数字と_で4〜15文字で入力してください",
          "username    | yama-da            | ユーザー名は半角英数字と_で4〜15文字で入力してください",
          "username    | やまだたろう       | ユーザー名は半角英数字と_で4〜15文字で入力してください",
          "username    | <なし>             | ユーザー名は半角英数字と_で4〜15文字で入力してください",
          "displayName | '   '              | 表示名は1〜50文字で入力してください",
          "displayName | <なし>             | 表示名は1〜50文字で入力してください",
          "email       | yamada.example.com | メールアドレスの形式が正しくありません",
          "email       | yamada@example     | メールアドレスの形式が正しくありません",
          "email       | ''                 | メールアドレスの形式が正しくありません",
          "password    | 1234567            | パスワードは8文字以上で入力してください",
          "password    | <なし>             | パスワードは8文字以上で入力してください"
        })
    void 入力チェックの違反は400で_その項目のエラーがちょうど1つ(String field, String value, String message)
        throws Exception {
      postJson("/api/auth/signup", signup(field, value))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(
              jsonPath("$.errors[?(@.field == '" + field + "')].message", contains(message)));
      verify(authService, never()).signup(any());
    }

    @Test
    void 長すぎる表示名_メールアドレス_パスワードは400() throws Exception {
      postJson("/api/auth/signup", signup("displayName", "あ".repeat(51)))
          .andExpect(jsonPath("$.errors[0].message").value("表示名は1〜50文字で入力してください"));
      postJson("/api/auth/signup", signup("email", "a".repeat(244) + "@example.com")) // 256文字
          .andExpect(jsonPath("$.errors[0].message").value("メールアドレスの形式が正しくありません"));
      postJson("/api/auth/signup", signup("password", "a".repeat(73)))
          .andExpect(jsonPath("$.errors[0].message").value("パスワードは72文字以内で入力してください"));
    }

    @Test
    void 複数の項目が違反していれば_まとめて返す() throws Exception {
      postJson(
              "/api/auth/signup",
              Map.of("username", "ab", "displayName", "", "email", "x", "password", "1"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void 重複していれば409で_どの項目かを返す() throws Exception {
      when(authService.signup(any()))
          .thenThrow(
              ApiException.conflict(
                  List.of(new ApiError.FieldError("email", "このメールアドレスはすでに登録されています"))));

      postJson("/api/auth/signup", signup("username", "yamada"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("ALREADY_REGISTERED"))
          .andExpect(jsonPath("$.errors[0].field").value("email"));
    }
  }

  @Nested
  class ログイン {

    @Test
    void ログインなしで呼べ_200でアクセストークンとCookieを返す() throws Exception {
      when(authService.login(any())).thenReturn(RESULT);

      postJson("/api/auth/login", Map.of("email", "yamada@example.com", "password", "password123"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.accessToken").value("access-token"))
          .andExpect(
              header()
                  .string(HttpHeaders.SET_COOKIE, containsString("refresh_token=refresh-token")));
    }

    @Test
    void メールアドレスとパスワードが空なら400() throws Exception {
      postJson("/api/auth/login", Map.of("email", " ", "password", ""))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.errors[?(@.field == 'email')].message", contains("メールアドレスを入力してください")))
          .andExpect(
              jsonPath("$.errors[?(@.field == 'password')].message", contains("パスワードを入力してください")));
      verify(authService, never()).login(any());
    }

    @Test
    void 違っていれば401で_Cookieは返さない() throws Exception {
      when(authService.login(any())).thenThrow(new ApiException(ErrorCode.INVALID_CREDENTIALS));

      postJson(
              "/api/auth/login",
              Map.of("email", "yamada@example.com", "password", "wrong-password"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
          .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }
  }

  @Nested
  class 再発行とログアウト {

    @Test
    void Cookieのリフレッシュトークンで再発行し_新しいCookieを返す() throws Exception {
      when(authService.refresh("old-token")).thenReturn(RESULT);

      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "old-token")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.accessToken").value("access-token"))
          .andExpect(
              header()
                  .string(HttpHeaders.SET_COOKIE, containsString("refresh_token=refresh-token")));
    }

    @Test
    void Cookieがない_空なら401で_Serviceを呼ばない() throws Exception {
      mockMvc
          .perform(post("/api/auth/refresh"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", " ")))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
      verify(authService, never()).refresh(anyString());
    }

    @Test
    void 再発行できないトークンは401() throws Exception {
      when(authService.refresh("used-token"))
          .thenThrow(new ApiException(ErrorCode.SESSION_EXPIRED));

      mockMvc
          .perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "used-token")))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void ログアウトは204で_リフレッシュトークンを無効にし_Cookieを期限0で消す() throws Exception {
      mockMvc
          .perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", "token")))
          .andExpect(status().isNoContent())
          .andExpect(content().string(""))
          .andExpect(
              header()
                  .string(
                      HttpHeaders.SET_COOKIE,
                      allOf(
                          containsString("refresh_token=;"),
                          containsString("Max-Age=0"),
                          containsString("Path=/api/auth"))));

      verify(authService).logout("token");
    }

    @Test
    void Cookieがなくてもログアウトは204で_Serviceは呼ばない() throws Exception {
      mockMvc
          .perform(post("/api/auth/logout"))
          .andExpect(status().isNoContent())
          .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
      verify(authService, never()).logout(anyString());
    }

    @Test
    void Cookieの中身が空白だけでもログアウトは204で_Serviceは呼ばない() throws Exception {
      mockMvc
          .perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", " ")))
          .andExpect(status().isNoContent());
      verify(authService, never()).logout(anyString());
    }
  }

  @Nested
  class ログイン中のユーザー {

    @Test
    void ログイン済みなら200でユーザー情報を返す() throws Exception {
      when(authService.me(7)).thenReturn(USER);

      mockMvc
          .perform(as(7, get("/api/auth/me")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(7))
          .andExpect(jsonPath("$.username").value("yamada"))
          .andExpect(jsonPath("$.iconUrl").isEmpty());
    }

    @Test
    void ログインなしなら401で_Serviceを呼ばない() throws Exception {
      mockMvc
          .perform(get("/api/auth/me"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
      verifyNoInteractions(authService);
    }
  }
}
