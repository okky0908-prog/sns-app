package com.okimoto.sns.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.okimoto.sns.backend.storage.StorageProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * テストが、開発用の PostgreSQL（localhost:5432）と LocalStack（localhost:4566）ではなく、Testcontainers
 * の使い捨てのコンテナにつながっていることを確かめる（Issue #89）。
 *
 * <p>開発用の DB にテストのデータが書き込まれると、開発や本番を想定した動きがおかしくなるため。
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class TestEnvironmentIsolationTest {

  @Autowired private DataSourceProperties dataSourceProperties;
  @Autowired private DataSource dataSource;
  @Autowired private StorageProperties storageProperties;

  @Test
  void データベースは開発用ではなくテスト用のコンテナを使う() {
    assertThat(dataSourceProperties.getUrl()).doesNotContain("localhost:5432");
    // 本番と同じ PostgreSQL で動いている（H2 などではない）
    String version = new JdbcTemplate(dataSource).queryForObject("SELECT version()", String.class);
    assertThat(version).startsWith("PostgreSQL 17");
  }

  @Test
  void 画像の保存先は開発用のLocalStackではなくテスト用のコンテナを使う() {
    assertThat(storageProperties.endpoint()).doesNotContain("localhost:4566");
    assertThat(storageProperties.imageBaseUrl()).doesNotContain("localhost:4566");
  }
}
