package com.okimoto.sns.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.support.LogCapture;
import com.okimoto.sns.backend.support.TestRows;
import com.okimoto.sns.backend.support.TestcontainersConfiguration;
import com.okimoto.sns.backend.user.UserMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 監査ログ（誰が何をしたか）とセキュリティのログ（攻撃・不正の疑い）。docs/observability.md「3.1 ログの種類」。
 *
 * <p>実際の API を呼んで、出たログを確かめる。テストごとにロールバックするので、DB にデータは残らない
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class AuditLogTest {

  private static final String PASSWORD = "secret-pass-123";

  @RegisterExtension final LogCapture logs = new LogCapture();

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtService jwtService;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;

  // ===== 道具 =====

  /** 監査ログのうち、指定した操作のもの */
  private List<ILoggingEvent> audit(String action) {
    return logs.from(AuditLog.class).stream()
        .filter(e -> action.equals(LogCapture.keyValues(e).get("event.action")))
        .toList();
  }

  private ILoggingEvent onlyAudit(String action) {
    assertThat(audit(action)).as("操作 %s の監査ログ", action).hasSize(1);
    return audit(action).getFirst();
  }

  private MvcResult signup(String username, String email) throws Exception {
    String body =
        objectMapper.writeValueAsString(
            Map.of(
                "username", username,
                "displayName", "表示名",
                "email", email,
                "password", PASSWORD));
    return mockMvc
        .perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn();
  }

  private MvcResult login(String email, String password) throws Exception {
    String body = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    return mockMvc
        .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private static Cookie refreshCookie(MvcResult result) {
    return result.getResponse().getCookie("refresh_token");
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B as(long userId, B request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userId));
  }

  private TestRows rows() {
    return new TestRows(userMapper, postMapper);
  }

  // ===== 認証 =====

  @Test
  void 新規登録したら_登録したユーザーのIDで記録する() throws Exception {
    long userId = json(signup("audit01", "audit01@example.com")).at("/user/id").asLong();

    ILoggingEvent log = onlyAudit("signup");
    assertThat(log.getLevel()).isEqualTo(Level.INFO);
    assertThat(log.getMDCPropertyMap()).containsEntry("user_id", String.valueOf(userId));
  }

  @Test
  void ログインしたら_そのユーザーのIDで記録する() throws Exception {
    long userId = json(signup("audit02", "audit02@example.com")).at("/user/id").asLong();

    login("audit02@example.com", PASSWORD);

    ILoggingEvent log = onlyAudit("login");
    assertThat(log.getLevel()).isEqualTo(Level.INFO);
    assertThat(log.getMDCPropertyMap()).containsEntry("user_id", String.valueOf(userId));
  }

  @Test
  void ログインした結果_アクセスログにもユーザーIDが入る() throws Exception {
    long userId = json(signup("audit03", "audit03@example.com")).at("/user/id").asLong();
    int before = logs.from(RequestLoggingFilter.class).size();

    login("audit03@example.com", PASSWORD);

    ILoggingEvent access = logs.from(RequestLoggingFilter.class).get(before);
    assertThat(access.getMDCPropertyMap()).containsEntry("user_id", String.valueOf(userId));
  }

  // 同値分割：パスワード違い・登録されていないメールアドレス（どちらも同じに扱う）
  @ParameterizedTest
  @CsvSource({"audit04@example.com, wrong-password", "nobody@example.com, " + PASSWORD})
  void ログインに失敗したら_一部を隠したメールアドレスで警告として記録する(String email, String password) throws Exception {
    signup("audit04", "audit04@example.com");

    login(email, password);

    ILoggingEvent log = onlyAudit("login_failed");
    assertThat(log.getLevel()).isEqualTo(Level.WARN);
    String masked = email.charAt(0) + "***@example.com";
    assertThat(LogCapture.keyValues(log)).containsEntry("user.email", masked);
    // メールアドレスそのものは、どのログにも出さない
    assertThat(logs.allText()).doesNotContain(email);
  }

  @Test
  void ログアウトしたら_そのユーザーのIDで記録する() throws Exception {
    MvcResult signedUp = signup("audit05", "audit05@example.com");
    long userId = json(signedUp).at("/user/id").asLong();

    mockMvc.perform(post("/api/auth/logout").cookie(refreshCookie(signedUp)));

    assertThat(onlyAudit("logout").getMDCPropertyMap())
        .containsEntry("user_id", String.valueOf(userId));
  }

  @Test
  void ログアウトしたトークンがもう一度使われたら_盗まれた疑いとして警告で記録する() throws Exception {
    MvcResult signedUp = signup("audit06", "audit06@example.com");
    long userId = json(signedUp).at("/user/id").asLong();
    Cookie cookie = refreshCookie(signedUp);
    mockMvc.perform(post("/api/auth/logout").cookie(cookie));

    mockMvc.perform(post("/api/auth/refresh").cookie(cookie));

    ILoggingEvent log = onlyAudit("refresh_token_reuse");
    assertThat(log.getLevel()).isEqualTo(Level.WARN);
    assertThat(log.getMDCPropertyMap()).containsEntry("user_id", String.valueOf(userId));
  }

  @Test
  void ふつうの再発行は記録しない() throws Exception {
    MvcResult signedUp = signup("audit07", "audit07@example.com");

    mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(signedUp)));

    assertThat(audit("refresh_token_reuse")).isEmpty();
  }

  // ===== 投稿・コメント・プロフィール =====

  @Test
  void 投稿を削除したら_投稿のIDを記録する() throws Exception {
    long userId = rows().user("audit08");
    long postId = rows().post(userId, "消す投稿", TestRows.T0);

    mockMvc.perform(as(userId, delete("/api/posts/" + postId))).andExpect(status().isNoContent());

    ILoggingEvent log = onlyAudit("post_delete");
    assertThat(LogCapture.keyValues(log)).containsEntry("post.id", String.valueOf(postId));
    assertThat(log.getMDCPropertyMap()).containsEntry("user_id", String.valueOf(userId));
    // 本文は出さない
    assertThat(logs.allText()).doesNotContain("消す投稿");
  }

  @Test
  void コメントを削除したら_コメントのIDを記録する() throws Exception {
    long userId = rows().user("audit09");
    long postId = rows().post(userId, "投稿", TestRows.T0);
    MvcResult created =
        mockMvc
            .perform(
                as(userId, post("/api/posts/" + postId + "/comments"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"消すコメント\"}"))
            .andReturn();
    long commentId = json(created).at("/id").asLong();

    mockMvc.perform(as(userId, delete("/api/comments/" + commentId))).andExpect(status().isOk());

    assertThat(LogCapture.keyValues(onlyAudit("comment_delete")))
        .containsEntry("comment.id", String.valueOf(commentId));
    assertThat(logs.allText()).doesNotContain("消すコメント");
  }

  @Test
  void プロフィールを変えたら記録する() throws Exception {
    long userId = rows().user("audit10");

    mockMvc
        .perform(
            as(userId, multipart(HttpMethod.PUT, "/api/users/me"))
                .param("displayName", "新しい名前")
                .param("bio", "新しい自己紹介"))
        .andExpect(status().isOk());

    assertThat(onlyAudit("profile_update").getMDCPropertyMap())
        .containsEntry("user_id", String.valueOf(userId));
    assertThat(logs.allText()).doesNotContain("新しい自己紹介");
  }

  @Test
  void 他人の投稿を削除しようとしたら_権限のない操作として警告で記録する() throws Exception {
    long author = rows().user("audit11");
    long other = rows().user("audit12");
    long postId = rows().post(author, "他人の投稿", TestRows.T0);

    mockMvc.perform(as(other, delete("/api/posts/" + postId))).andExpect(status().isForbidden());

    ILoggingEvent log = onlyAudit("forbidden");
    assertThat(log.getLevel()).isEqualTo(Level.WARN);
    assertThat(log.getMDCPropertyMap()).containsEntry("user_id", String.valueOf(other));
    assertThat(audit("post_delete")).isEmpty();
  }

  // ===== 出さない情報 =====

  @Test
  void 登録からログアウトまでの間に_パスワード_トークン_メールアドレスをどのログにも出さない() throws Exception {
    MvcResult signedUp = signup("audit13", "audit13@example.com");
    MvcResult loggedIn = login("audit13@example.com", PASSWORD);
    MvcResult refreshed =
        mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie(loggedIn))).andReturn();
    mockMvc.perform(post("/api/auth/logout").cookie(refreshCookie(refreshed)));

    String all = logs.allText();
    assertThat(all).doesNotContain(PASSWORD).doesNotContain("audit13@example.com");
    for (MvcResult result : List.of(signedUp, loggedIn, refreshed)) {
      assertThat(all)
          .doesNotContain(json(result).at("/accessToken").asString())
          .doesNotContain(refreshCookie(result).getValue());
    }
  }

  // ===== メールアドレスの隠し方 =====

  @ParameterizedTest
  @CsvSource({
    "alice@example.com, a***@example.com",
    "a@example.com, a***@example.com",
    "Bob.Smith@mail.example.co.jp, B***@mail.example.co.jp",
    "not-an-email, ***",
    "@example.com, ***",
  })
  void メールアドレスは_先頭の1文字とドメインだけを残す(String email, String masked) {
    assertThat(AuditLog.maskEmail(email)).isEqualTo(masked);
  }
}
