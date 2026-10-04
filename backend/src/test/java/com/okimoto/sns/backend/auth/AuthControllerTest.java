package com.okimoto.sns.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 認証 API（A-01〜A-05）のテスト。テストごとにロールバックするので、DB にデータは残らない。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private RefreshTokenMapper refreshTokenMapper;

  // ===== 道具 =====

  private static Map<String, Object> signupBody(
      String username, String displayName, String email, String password) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("displayName", displayName);
    body.put("email", email);
    body.put("password", password);
    return body;
  }

  private ResultActions postJson(String path, Object body) throws Exception {
    return mockMvc.perform(
        post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  private String signupAndGetToken(String username, String email) throws Exception {
    String json =
        postJson("/api/auth/signup", signupBody(username, "テスト太郎", email, "password123"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode node = objectMapper.readTree(json);
    return node.get("accessToken").asString();
  }

  /** Set-Cookie ヘッダーからリフレッシュトークンの値を取り出す */
  private static String refreshTokenOf(MvcResult result) {
    String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).startsWith(AuthController.REFRESH_TOKEN_COOKIE + "=");
    return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
  }

  private String signupAndGetRefreshToken(String username, String email) throws Exception {
    return refreshTokenOf(
        postJson("/api/auth/signup", signupBody(username, "テスト太郎", email, "password123"))
            .andExpect(status().isCreated())
            .andReturn());
  }

  private ResultActions postRefresh(String refreshToken) throws Exception {
    var request = post("/api/auth/refresh");
    if (refreshToken != null) {
      request.cookie(new Cookie(AuthController.REFRESH_TOKEN_COOKIE, refreshToken));
    }
    return mockMvc.perform(request);
  }

  private ResultActions getMe(String authorization) throws Exception {
    var request = get("/api/auth/me");
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return mockMvc.perform(request);
  }

  // ===== A-01 新規登録 =====

  @Test
  void signup_正しい内容で登録でき_トークンとユーザー情報が返る() throws Exception {
    postJson(
            "/api/auth/signup",
            signupBody("test_user1", "  テスト太郎  ", "Test.User1@Example.com", "password123"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").value(notNullValue()))
        .andExpect(jsonPath("$.user.id").value(notNullValue()))
        .andExpect(jsonPath("$.user.username").value("test_user1"))
        .andExpect(jsonPath("$.user.displayName").value("テスト太郎"))
        .andExpect(jsonPath("$.user.iconUrl").value(nullValue()));

    // メールアドレスは小文字にそろえて保存し、パスワードは BCrypt でハッシュ化して保存する
    User saved = userMapper.findByEmail("test.user1@example.com").orElseThrow();
    assertThat(saved.getPasswordHash()).isNotEqualTo("password123").startsWith("$2");
    assertThat(passwordEncoder.matches("password123", saved.getPasswordHash())).isTrue();
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void signup_空で送ると項目ごとに400() throws Exception {
    postJson("/api/auth/signup", Map.of())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("入力内容に誤りがあります"))
        .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'displayName')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
  }

  @Test
  void signup_ユーザー名の形式が違うと400() throws Exception {
    for (String username : new String[] {"abc", "abcdefghijklmnop", "山田太郎", "user-name"}) {
      postJson("/api/auth/signup", signupBody(username, "名前", "a@example.com", "password123"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].message").value("ユーザー名は半角英数字と_で4〜15文字で入力してください"));
    }
  }

  @Test
  void signup_表示名は前後の空白を除いて1から50文字_絵文字も1文字と数える() throws Exception {
    postJson("/api/auth/signup", signupBody("emptyname", "   ", "a@example.com", "password123"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("displayName"));
    postJson(
            "/api/auth/signup",
            signupBody("emojiname", "😀".repeat(50), "emoji@example.com", "password123"))
        .andExpect(status().isCreated());
    postJson(
            "/api/auth/signup",
            signupBody("emojiname2", "😀".repeat(51), "emoji2@example.com", "password123"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void signup_パスワードは8から72文字_72バイトを超える全角はエラー() throws Exception {
    postJson("/api/auth/signup", signupBody("shortpw", "名前", "s@example.com", "1234567"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("パスワードは8文字以上で入力してください"));
    postJson("/api/auth/signup", signupBody("longpw", "名前", "l@example.com", "a".repeat(73)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("パスワードは72文字以内で入力してください"));
    postJson("/api/auth/signup", signupBody("maxpw", "名前", "m@example.com", "a".repeat(72)))
        .andExpect(status().isCreated());
    // 全角30文字 = 90バイト（文字数は72以内だが BCrypt の72バイトを超える）
    postJson("/api/auth/signup", signupBody("zenkakupw", "名前", "z@example.com", "あ".repeat(30)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("password"));
  }

  @Test
  void signup_メールアドレスの形式が違うと400() throws Exception {
    postJson("/api/auth/signup", signupBody("mailuser", "名前", "not-an-email", "password123"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("メールアドレスの形式が正しくありません"));
  }

  @Test
  void signup_ユーザー名の重複は大文字小文字を区別せず409() throws Exception {
    signupAndGetToken("dupuser", "dup1@example.com");
    postJson("/api/auth/signup", signupBody("DupUser", "名前", "dup2@example.com", "password123"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].field").value("username"))
        .andExpect(jsonPath("$.errors[0].message").value("このユーザー名はすでに使われています"));
  }

  @Test
  void signup_メールアドレスの重複は大文字小文字を区別せず409() throws Exception {
    signupAndGetToken("mailowner", "owner@example.com");
    postJson("/api/auth/signup", signupBody("another1", "名前", "OWNER@example.com", "password123"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].field").value("email"))
        .andExpect(jsonPath("$.errors[0].message").value("このメールアドレスはすでに登録されています"));
  }

  @Test
  void signup_ユーザー名とメールアドレスが両方重複していれば両方返す() throws Exception {
    signupAndGetToken("bothdup", "both@example.com");
    postJson("/api/auth/signup", signupBody("bothdup", "名前", "both@example.com", "password123"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors", hasSize(2)));
  }

  @Test
  void signup_JSONが壊れていると400() throws Exception {
    mockMvc
        .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("リクエストの形式が正しくありません"));
  }

  // ===== A-02 ログイン =====

  @Test
  void login_正しいメールアドレスとパスワードでログインできる_メールは大文字小文字を区別しない() throws Exception {
    signupAndGetToken("loginuser", "login@example.com");
    postJson("/api/auth/login", Map.of("email", "  LOGIN@Example.com ", "password", "password123"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value(notNullValue()))
        .andExpect(jsonPath("$.user.username").value("loginuser"));
  }

  @Test
  void login_パスワード違いと未登録のメールアドレスは同じ401() throws Exception {
    signupAndGetToken("wrongpw", "wrongpw@example.com");
    String message = "メールアドレスまたはパスワードが正しくありません";
    postJson("/api/auth/login", Map.of("email", "wrongpw@example.com", "password", "different1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.message").value(message));
    postJson("/api/auth/login", Map.of("email", "nobody@example.com", "password", "password123"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value(message));
    postJson("/api/auth/login", Map.of("email", "wrongpw@example.com", "password", "あ".repeat(30)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value(message));
  }

  @Test
  void login_空で送ると400() throws Exception {
    postJson("/api/auth/login", Map.of("email", "", "password", ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors", hasSize(2)));
  }

  // ===== A-03 ログイン中のユーザー情報・認可 =====

  @Test
  void me_登録時のトークンでログイン中のユーザー情報を取得できる() throws Exception {
    String token = signupAndGetToken("meuser", "me@example.com");
    getMe("Bearer " + token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("meuser"))
        .andExpect(jsonPath("$.displayName").value("テスト太郎"));
  }

  @Test
  void me_トークンなし_形式違い_改ざん_期限切れはすべて401() throws Exception {
    String token = signupAndGetToken("authcheck", "authcheck@example.com");
    long userId = userMapper.findByEmail("authcheck@example.com").orElseThrow().getId();
    String expired =
        new JwtService(
                jwtProperties,
                Clock.fixed(Instant.now().minus(Duration.ofHours(25)), ZoneOffset.UTC))
            .issue(userId);

    for (String authorization :
        new String[] {
          null,
          token, // "Bearer " が付いていない
          "Bearer not-a-jwt",
          "Bearer " + token.substring(0, token.length() - 2) + "xx",
          "Bearer " + expired
        }) {
      getMe(authorization)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.status").value(401))
          .andExpect(jsonPath("$.message").value("ログインが必要です"));
    }
  }

  // ===== リフレッシュトークン（A-04 再発行・A-05 ログアウト） =====

  @Test
  void 登録とログインでリフレッシュトークンがHttpOnlyのCookieで返り_JSONには入らない() throws Exception {
    for (ResultActions result :
        new ResultActions[] {
          postJson(
              "/api/auth/signup",
              signupBody("cookieuser", "名前", "cookie@example.com", "password123")),
          postJson(
              "/api/auth/login", Map.of("email", "cookie@example.com", "password", "password123"))
        }) {
      result
          .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=")))
          .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
          .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
          .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/auth")))
          .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=1209600")))
          .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }
    // DB にはトークンそのものではなくハッシュを保存する
    String token = signupAndGetRefreshToken("hashcheck", "hashcheck@example.com");
    assertThat(refreshTokenMapper.findByTokenHash(token)).isEmpty();
    assertThat(refreshTokenMapper.findByTokenHash(RefreshTokenService.hash(token))).isPresent();
  }

  @Test
  void refresh_新しいアクセストークンが返り_リフレッシュトークンも新しいものに交換される() throws Exception {
    String oldRefresh = signupAndGetRefreshToken("refreshuser", "refresh@example.com");
    MvcResult result =
        postRefresh(oldRefresh)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value(notNullValue()))
            .andExpect(jsonPath("$.user.username").value("refreshuser"))
            .andReturn();
    String newRefresh = refreshTokenOf(result);
    assertThat(newRefresh).isNotEqualTo(oldRefresh);

    String accessToken =
        objectMapper
            .readTree(result.getResponse().getContentAsString())
            .get("accessToken")
            .asString();
    getMe("Bearer " + accessToken).andExpect(status().isOk());
    postRefresh(newRefresh).andExpect(status().isOk());
  }

  @Test
  void refresh_使用済みのトークンが再び使われたら401_そのユーザーのトークンをすべて無効にする() throws Exception {
    String oldRefresh = signupAndGetRefreshToken("reuseuser", "reuse@example.com");
    String newRefresh =
        refreshTokenOf(postRefresh(oldRefresh).andExpect(status().isOk()).andReturn());

    // 盗まれた古いトークンが使われた
    postRefresh(oldRefresh)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value(AuthService.SESSION_EXPIRED));
    // 正規の利用者が持つ新しいトークンも無効になっている（もう一度ログインが必要）
    postRefresh(newRefresh).andExpect(status().isUnauthorized());
  }

  @Test
  void refresh_Cookieなし_知らないトークン_期限切れはすべて401() throws Exception {
    postRefresh(null)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.message").value(AuthService.SESSION_EXPIRED));
    postRefresh("unknown-token").andExpect(status().isUnauthorized());

    signupAndGetRefreshToken("expireduser", "expired@example.com");
    long userId = userMapper.findByEmail("expired@example.com").orElseThrow().getId();
    OffsetDateTime now = OffsetDateTime.now();
    refreshTokenMapper.insert(
        new RefreshToken(
            userId,
            RefreshTokenService.hash("expired-token"),
            now.minusMinutes(1),
            now.minusDays(14)));
    postRefresh("expired-token").andExpect(status().isUnauthorized());
  }

  @Test
  void logout_リフレッシュトークンを無効にしてCookieを消す() throws Exception {
    String refresh = signupAndGetRefreshToken("logoutuser", "logout@example.com");
    mockMvc
        .perform(
            post("/api/auth/logout")
                .cookie(new Cookie(AuthController.REFRESH_TOKEN_COOKIE, refresh)))
        .andExpect(status().isNoContent())
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    postRefresh(refresh).andExpect(status().isUnauthorized());

    // Cookie がなくても（すでにログアウト済みでも）エラーにしない
    mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
  }

  @Test
  void ログインが必要なAPIは未ログインだと401_ヘルスチェックはログイン不要() throws Exception {
    mockMvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
