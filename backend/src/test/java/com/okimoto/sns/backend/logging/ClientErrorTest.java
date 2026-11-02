package com.okimoto.sns.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.support.LogCapture;
import com.okimoto.sns.backend.support.TestcontainersConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** 画面で起きたエラーの報告（A-90。docs/observability.md「3.1 ログの種類」の画面のエラー） */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientErrorTest {

  @RegisterExtension final LogCapture logs = new LogCapture();

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtService jwtService;

  private static Map<String, Object> report() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("kind", "render");
    body.put("message", "Cannot read properties of undefined (reading 'id')");
    body.put(
        "stack",
        "TypeError: Cannot read properties of undefined\n    at PostCard (PostCard.tsx:12:3)");
    body.put("path", "/posts/10");
    return body;
  }

  private ResultActions send(Map<String, Object> body, String... headers) throws Exception {
    var request =
        post("/api/client-errors")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body));
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    return mockMvc.perform(request);
  }

  private List<ILoggingEvent> clientErrorLogs() {
    return logs.from(ClientErrorController.class);
  }

  private ILoggingEvent onlyClientErrorLog() {
    assertThat(clientErrorLogs()).hasSize(1);
    return clientErrorLogs().getFirst();
  }

  @Test
  void ログインしていなくても送れ_警告としてエラーの中身を残す() throws Exception {
    send(report(), HttpHeaders.USER_AGENT, "Mozilla/5.0 テスト").andExpect(status().isNoContent());

    ILoggingEvent log = onlyClientErrorLog();
    // 誰でも送れるので、偽の報告をたくさん送られても P1 のアラートが鳴らないよう、ERROR にはしない
    assertThat(log.getLevel()).isEqualTo(Level.WARN);
    assertThat(LogCapture.keyValues(log))
        .containsEntry("event.action", "client_error")
        .containsEntry("error.type", "render")
        .containsEntry("error.message", "Cannot read properties of undefined (reading 'id')")
        .containsEntry(
            "error.stack_trace",
            "TypeError: Cannot read properties of undefined\n    at PostCard (PostCard.tsx:12:3)")
        .containsEntry("url.path", "/posts/10")
        .containsEntry("user_agent.original", "Mozilla/5.0 テスト");
    assertThat(log.getFormattedMessage())
        .isEqualTo(
            "画面でエラーが起きました（render /posts/10）: Cannot read properties of undefined (reading 'id')");
  }

  @Test
  void ログイン中なら_ユーザーIDも残す() throws Exception {
    send(report(), HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(42L));

    assertThat(onlyClientErrorLog().getMDCPropertyMap()).containsEntry("user_id", "42");
  }

  @Test
  void アクセストークンが正しくなくても_401にせず受け付ける() throws Exception {
    // 期限切れのトークンを持ったまま画面でエラーが起きることがある。報告は捨てずに受け取る
    send(report(), HttpHeaders.AUTHORIZATION, "Bearer expired-or-tampered")
        .andExpect(status().isNoContent());

    assertThat(onlyClientErrorLog().getMDCPropertyMap()).doesNotContainKey("user_id");
  }

  @Test
  void スタックトレースがなくても送れる() throws Exception {
    Map<String, Object> body = report();
    body.remove("stack");

    send(body).andExpect(status().isNoContent());

    assertThat(LogCapture.keyValues(onlyClientErrorLog())).doesNotContainKey("error.stack_trace");
  }

  @Test
  void URLの検索条件や後ろの部分は_念のためサーバーでも切る() throws Exception {
    Map<String, Object> body = report();
    body.put("path", "/search?q=やまだ#top");

    send(body).andExpect(status().isNoContent());

    assertThat(LogCapture.keyValues(onlyClientErrorLog())).containsEntry("url.path", "/search");
    assertThat(logs.allText()).doesNotContain("やまだ");
  }

  @Test
  void ブラウザの情報が長すぎれば_300文字で切る() throws Exception {
    send(report(), HttpHeaders.USER_AGENT, "a".repeat(301));

    assertThat(LogCapture.keyValues(onlyClientErrorLog()))
        .containsEntry("user_agent.original", "a".repeat(300));
  }

  // ===== 入力チェック（境界値・同値分割） =====

  @ParameterizedTest
  @CsvSource({
    "message, 1000, 204",
    "message, 1001, 400",
    "stack, 8000, 204",
    "stack, 8001, 400",
    "path, 200, 204",
    "path, 201, 400",
  })
  void 長さの上限(String field, int length, int expectedStatus) throws Exception {
    Map<String, Object> body = report();
    body.put(field, field.equals("path") ? "/" + "a".repeat(length - 1) : "a".repeat(length));

    send(body).andExpect(status().is(expectedStatus));
  }

  @ParameterizedTest
  @ValueSource(strings = {"render", "error", "unhandledrejection"})
  void 種類は3つのどれか(String kind) throws Exception {
    Map<String, Object> body = report();
    body.put("kind", kind);

    send(body).andExpect(status().isNoContent());
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "kind, console",
        "kind, NULL",
        "message, ''",
        "message, '   '",
        "message, NULL",
        "path, posts/10",
        "path, NULL",
      },
      nullValues = "NULL")
  void 正しくない報告は400にし_ログに残さない(String field, String value) throws Exception {
    Map<String, Object> body = report();
    body.put(field, value);

    send(body).andExpect(status().isBadRequest());

    assertThat(clientErrorLogs()).isEmpty();
  }
}
