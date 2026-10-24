package com.okimoto.sns.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.support.LogCapture;
import com.okimoto.sns.backend.support.TestRows;
import com.okimoto.sns.backend.support.TestcontainersConfiguration;
import com.okimoto.sns.backend.user.UserMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * リクエスト ID とアクセスログ（docs/observability.md「3. ログの設計」）。
 *
 * <p>ログの中身（MDC・キーと値）を受け取って確かめるので、出力の形式（テキスト・JSON）には左右されない
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class RequestLoggingTest {

  @RegisterExtension final LogCapture logs = new LogCapture();

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;

  /** アクセスログ（1リクエスト1行） */
  private List<ILoggingEvent> accessLogs() {
    return logs.from(RequestLoggingFilter.class);
  }

  private ILoggingEvent onlyAccessLog() {
    assertThat(accessLogs()).hasSize(1);
    return accessLogs().getFirst();
  }

  // ===== リクエスト ID =====

  @Test
  void リクエストIDを作って応答のヘッダーに返し_アクセスログにも同じIDを入れる() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/no-such-api"))
            .andExpect(header().exists(RequestLoggingFilter.REQUEST_ID_HEADER))
            .andReturn();

    String requestId = result.getResponse().getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
    assertThat(requestId).matches("[0-9a-f]{32}");
    assertThat(onlyAccessLog().getMDCPropertyMap()).containsEntry("request_id", requestId);
  }

  @Test
  void リクエストごとに違うIDにする() throws Exception {
    String first =
        mockMvc
            .perform(get("/api/no-such-api"))
            .andReturn()
            .getResponse()
            .getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
    String second =
        mockMvc
            .perform(get("/api/no-such-api"))
            .andReturn()
            .getResponse()
            .getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void ALBが付けたトレースIDがあれば_それをリクエストIDにする() throws Exception {
    String traceId =
        "Self=1-67891234-12456789abcdef012345678;Root=1-67891233-abcdef012345678912345678";

    mockMvc
        .perform(get("/api/no-such-api").header("X-Amzn-Trace-Id", traceId))
        .andExpect(header().string(RequestLoggingFilter.REQUEST_ID_HEADER, traceId));

    assertThat(onlyAccessLog().getMDCPropertyMap()).containsEntry("request_id", traceId);
  }

  // エラー推測：ヘッダーは外から自由に送れるので、ログを壊す文字（改行など）や長すぎる値はそのまま使わない
  @ParameterizedTest
  @ValueSource(strings = {"Root=1-abc\nINFO 偽のログ", "Root=1-abc\"}", "", " "})
  void トレースIDに使えない文字があれば_使わずに作る(String traceId) throws Exception {
    String requestId =
        mockMvc
            .perform(get("/api/no-such-api").header("X-Amzn-Trace-Id", traceId))
            .andReturn()
            .getResponse()
            .getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);

    assertThat(requestId).matches("[0-9a-f]{32}");
  }

  @Test
  void トレースIDが長すぎれば_使わずに作る() throws Exception {
    String tooLong = "Root=1-" + "a".repeat(250);

    String requestId =
        mockMvc
            .perform(get("/api/no-such-api").header("X-Amzn-Trace-Id", tooLong))
            .andReturn()
            .getResponse()
            .getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);

    assertThat(requestId).matches("[0-9a-f]{32}");
  }

  @Test
  void リクエストが終わったら_リクエストIDなどを残さない() throws Exception {
    mockMvc.perform(
        get("/api/posts/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(1L)));

    // MockMvc は同じスレッドで動く。次のリクエスト（同じスレッドを使い回す）に持ち越さない
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  // ===== アクセスログ =====

  @Test
  void アクセスログは1リクエスト1行で_メソッド_パス_状態コード_時間を出す() throws Exception {
    // ログインしていないと、存在しない URL でも先に 401 になるので、ログインして呼ぶ
    mockMvc.perform(
        get("/api/no-such-api")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(1L)));

    ILoggingEvent log = onlyAccessLog();
    assertThat(log.getLevel()).isEqualTo(Level.INFO);
    Map<String, Object> values = LogCapture.keyValues(log);
    assertThat(values)
        .containsEntry("http.method", "GET")
        .containsEntry("url.path", "/api/no-such-api")
        .containsEntry("http.status", "404")
        .containsKey("duration_ms");
    assertThat(Long.parseLong((String) values.get("duration_ms"))).isNotNegative();
    // ローカルのテキストの出力でも読めるように、文言にも主な項目を入れる
    assertThat(log.getFormattedMessage()).startsWith("GET /api/no-such-api 404 ");
  }

  @Test
  void ログイン中ならユーザーIDを入れる() throws Exception {
    long userId = new TestRows(userMapper, postMapper).user("logger01");

    mockMvc.perform(
        get("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userId)));

    assertThat(onlyAccessLog().getMDCPropertyMap())
        .containsEntry("user_id", String.valueOf(userId));
  }

  @Test
  void ログインしていなければユーザーIDを入れない() throws Exception {
    mockMvc.perform(get("/api/auth/me"));

    assertThat(onlyAccessLog().getMDCPropertyMap()).doesNotContainKey("user_id");
  }

  @Test
  void エラーで返したときは_エラーコードを入れる() throws Exception {
    mockMvc.perform(get("/api/auth/me")); // ログインしていない → 401

    ILoggingEvent log = onlyAccessLog();
    assertThat(LogCapture.keyValues(log)).containsEntry("http.status", "401");
    assertThat(log.getMDCPropertyMap()).containsEntry("error_code", "UNAUTHENTICATED");
  }

  @Test
  void Controllerの先で起きたエラーも_エラーコードを入れる() throws Exception {
    mockMvc.perform(
        get("/api/posts/999999")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(1L)));

    assertThat(onlyAccessLog().getMDCPropertyMap()).containsEntry("error_code", "POST_NOT_FOUND");
  }

  @Test
  void 成功したときはエラーコードを入れない() throws Exception {
    long userId = new TestRows(userMapper, postMapper).user("logger02");

    mockMvc.perform(
        get("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userId)));

    assertThat(onlyAccessLog().getMDCPropertyMap()).doesNotContainKey("error_code");
  }

  @Test
  void URLの検索条件は出さない() throws Exception {
    // 検索のキーワードは利用者の入力なので、ログに残さない（docs/observability.md「3.5 出さない情報」）。
    // ブラウザと同じく URL に ?q= を書いて送る（.param() で渡すと、URL の ? 以降としては扱われない）
    mockMvc.perform(
        get("/api/users/search?q={q}", "やまだ")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(1L)));

    assertThat(LogCapture.keyValues(onlyAccessLog()))
        .containsEntry("url.path", "/api/users/search");
    assertThat(logs.allText()).doesNotContain("やまだ").doesNotContain("q=");
  }

  @Test
  void ヘルスチェックはアクセスログに出さない() throws Exception {
    // ALB が数秒ごとに呼ぶので、出すと量が多いわりに役に立たない（docs/observability.md「4.3 費用の考え方」）
    mockMvc.perform(get("/api/health"));

    assertThat(accessLogs()).isEmpty();
  }
}
