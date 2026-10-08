package com.okimoto.sns.backend.support;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis が DB に送った SQL の数を数える（N+1 問題の検出用）。
 *
 * <p>テストクラスに {@code @Import(SqlCounter.Config.class)} を付けると使える。MyBatis のキャッシュで DB に送られなかった SQL
 * は数えない（実際に DB に送った数だけを数える）。
 */
@Intercepts(
    @Signature(
        type = StatementHandler.class,
        method = "prepare",
        args = {Connection.class, Integer.class}))
public class SqlCounter implements Interceptor {

  private final AtomicInteger count = new AtomicInteger();

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    count.incrementAndGet();
    return invocation.proceed();
  }

  /** 数え直す（テストデータの登録などで発行した SQL を数に含めないため） */
  public void reset() {
    count.set(0);
  }

  public int count() {
    return count.get();
  }

  /** MyBatis の自動設定は、Bean になっている Interceptor を組み込む */
  @TestConfiguration
  public static class Config {

    @Bean
    SqlCounter sqlCounter() {
      return new SqlCounter();
    }
  }
}
