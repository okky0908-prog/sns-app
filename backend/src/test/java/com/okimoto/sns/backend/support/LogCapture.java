package com.okimoto.sns.backend.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * テストの間に出たログを受け取る（docs/observability.md「3. ログの設計」を確かめるため）。
 *
 * <p>使い方：{@code @RegisterExtension final LogCapture logs = new LogCapture();}
 *
 * <p>出力された文字列ではなく、ログの中身（レベル・文言・MDC・キーと値）を受け取るので、出力の形式（ローカルはテキスト、本番は JSON）に左右されない
 */
public final class LogCapture implements BeforeEachCallback, AfterEachCallback {

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @Override
  public void beforeEach(ExtensionContext context) {
    appender.start();
    root().addAppender(appender);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    root().detachAppender(appender);
    appender.stop();
  }

  private static Logger root() {
    return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  /** 受け取ったログ（古い順） */
  public List<ILoggingEvent> events() {
    return List.copyOf(appender.list);
  }

  /** 指定したロガー（クラス）のログ */
  public List<ILoggingEvent> from(Class<?> loggerClass) {
    return events().stream().filter(e -> e.getLoggerName().equals(loggerClass.getName())).toList();
  }

  /** 条件に合うログ */
  public List<ILoggingEvent> matching(Predicate<ILoggingEvent> condition) {
    return events().stream().filter(condition).toList();
  }

  /** 指定したレベル以上のログ */
  public List<ILoggingEvent> atLeast(Level level) {
    return matching(e -> e.getLevel().isGreaterOrEqual(level));
  }

  /** ログのキーと値（{@code log.atInfo().addKeyValue(...)} で付けたもの） */
  public static Map<String, Object> keyValues(ILoggingEvent event) {
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    return pairs == null
        ? Map.of()
        : pairs.stream().collect(Collectors.toMap(p -> p.key, p -> String.valueOf(p.value)));
  }

  /**
   * 出力されうるものをすべてつないだ文字列（文言・MDC・キーと値・例外）。秘密情報が出ていないかを確かめるのに使う。
   *
   * <p>JSON でもテキストでも、ここに含まれないものは出力されない
   */
  public String allText() {
    return events().stream().map(LogCapture::textOf).collect(Collectors.joining("\n"));
  }

  private static String textOf(ILoggingEvent event) {
    StringBuilder text = new StringBuilder(event.getFormattedMessage());
    text.append(' ').append(event.getMDCPropertyMap());
    text.append(' ').append(keyValues(event));
    for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
      text.append(' ').append(t.getClassName()).append(": ").append(t.getMessage());
    }
    return text.toString();
  }
}
