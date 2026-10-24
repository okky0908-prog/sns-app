package com.okimoto.sns.backend.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * リクエスト ID とアクセスログ（docs/observability.md「3. ログの設計」）。
 *
 * <ul>
 *   <li>リクエスト ID を決めて MDC に入れ、そのリクエストの間のすべてのログに付ける。応答のヘッダー（{@value #REQUEST_ID_HEADER}）にも返す
 *   <li>ALB が付けるトレース ID（{@value #TRACE_ID_HEADER}）があれば、それをリクエスト ID にする（ALB のアクセスログとつながる）。
 *       ヘッダーは外から自由に送れるので、決まった文字だけでできた、長すぎない値のときだけ使う（改行などでログを壊されないように）
 *   <li>リクエストが終わったら、アクセスログを1行出す。ヘルスチェックは量が多いわりに役に立たないので出さない
 * </ul>
 *
 * <p>認証（Spring Security）より前に動くよう、一番外側に置く。401・403 もアクセスログに残すため
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  static final String TRACE_ID_HEADER = "X-Amzn-Trace-Id";

  /** ALB のトレース ID の形（例：Root=1-67891233-abcdef012345678912345678;Sampled=1） */
  private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9=;\\-]{1,200}");

  private static final String HEALTH_CHECK_PATH = "/api/health";

  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    String requestId = requestIdOf(request);
    MDC.put(LogFields.REQUEST_ID, requestId);
    response.setHeader(REQUEST_ID_HEADER, requestId);
    boolean failed = false;
    try {
      filterChain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException e) {
      failed = true;
      throw e;
    } finally {
      try {
        if (!HEALTH_CHECK_PATH.equals(request.getRequestURI())) {
          // 例外がここまで届いたときは、このあとサーブレットコンテナが 500 で返す
          int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
          logAccess(request, status, (System.nanoTime() - start) / 1_000_000);
        }
      } finally {
        // 同じスレッドで次に動くリクエストに持ち越さない
        MDC.clear();
      }
    }
  }

  private static String requestIdOf(HttpServletRequest request) {
    String traceId = request.getHeader(TRACE_ID_HEADER);
    if (traceId != null && SAFE_TRACE_ID.matcher(traceId).matches()) {
      return traceId;
    }
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** パスには URL の検索条件（?q= など）を含めない。利用者の入力なので（docs/observability.md「3.5 出さない情報」） */
  private static void logAccess(HttpServletRequest request, int status, long durationMs) {
    String method = request.getMethod();
    String path = request.getRequestURI();
    log.atInfo()
        .addKeyValue("http.method", method)
        .addKeyValue("url.path", path)
        .addKeyValue("http.status", status)
        .addKeyValue("duration_ms", durationMs)
        .log("{} {} {} {}ms", method, path, status, durationMs);
  }
}
