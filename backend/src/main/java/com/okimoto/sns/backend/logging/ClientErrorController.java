package com.okimoto.sns.backend.logging;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画面で起きたエラーを受け取ってログに残す（docs/observability.md「3.1 ログの種類」の画面のエラー）。
 *
 * <ul>
 *   <li>ログイン画面で起きたエラーも知りたいので、ログインなしで送れる。ログイン中ならユーザー ID も残る（MDC）
 *   <li>誰でも送れるので、偽の報告をたくさん送られても P1 のアラートが鳴らないよう、ERROR ではなく WARN にする。数は監視項目 A12 で見る
 *   <li>URL の検索条件・後ろの部分（? と # 以降）は、画面側でも除いて送るが、念のためここでも切る
 * </ul>
 */
@RestController
@Tag(name = "画面のエラー")
public class ClientErrorController {

  private static final Logger log = LoggerFactory.getLogger(ClientErrorController.class);
  static final int USER_AGENT_MAX = 300;

  /** A-90 画面で起きたエラーの報告。ログイン中なら、アクセストークンを付けるとユーザー ID も残る。 */
  @PostMapping("/api/client-errors")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void report(
      @Valid @RequestBody ClientErrorReport report,
      // ブラウザが自動で付けるので、API 仕様書には載せない
      @Parameter(hidden = true) @RequestHeader(name = HttpHeaders.USER_AGENT, required = false)
          String userAgent) {
    String path = withoutQuery(report.path());
    LoggingEventBuilder event =
        log.atWarn()
            .addKeyValue("event.action", "client_error")
            .addKeyValue("error.type", report.kind())
            .addKeyValue("error.message", report.message())
            .addKeyValue("url.path", path);
    if (report.stack() != null) {
      event = event.addKeyValue("error.stack_trace", report.stack());
    }
    if (userAgent != null) {
      event = event.addKeyValue("user_agent.original", truncate(userAgent, USER_AGENT_MAX));
    }
    event.log("画面でエラーが起きました（{} {}）: {}", report.kind(), path, report.message());
  }

  private static String withoutQuery(String path) {
    int end = path.length();
    for (char c : new char[] {'?', '#'}) {
      int index = path.indexOf(c);
      if (index >= 0) {
        end = Math.min(end, index);
      }
    }
    return path.substring(0, end);
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
