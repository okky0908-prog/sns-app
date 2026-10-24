package com.okimoto.sns.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 本番（production）のログの設定。docs/observability.md「3.2 形式：構造化ログ（JSON）」。
 *
 * <p>ローカル開発（application.yml）は読みやすいテキストのまま、本番だけ JSON（ECS）で出す
 */
class ProductionLoggingConfigTest {

  private static Object property(String file, String name) throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
    return sources.stream()
        .map(source -> source.getProperty(name))
        .filter(value -> value != null)
        .findFirst()
        .orElse(null);
  }

  @Test
  void 本番では_ログをJSONのECS形式で出す() throws IOException {
    assertThat(property("application-production.yml", "logging.structured.format.console"))
        .hasToString("ecs");
  }

  @Test
  void 本番のログには_サービス名と環境を入れる() throws IOException {
    assertThat(property("application-production.yml", "logging.structured.ecs.service.name"))
        .hasToString("sns-backend");
    assertThat(property("application-production.yml", "logging.structured.ecs.service.environment"))
        .hasToString("production");
  }

  @Test
  void 本番のログには_デプロイのときに渡したバージョンを入れる() throws IOException {
    // 「今回のリリースからエラーが増えた」を見分けるため（docs/observability.md「3.3 必ず入れる項目」）
    assertThat(property("application-production.yml", "logging.structured.ecs.service.version"))
        .hasToString("${APP_VERSION:unknown}");
  }

  @Test
  void ローカル開発では_読みやすいテキストのまま() throws IOException {
    assertThat(property("application.yml", "logging.structured.format.console")).isNull();
  }
}
