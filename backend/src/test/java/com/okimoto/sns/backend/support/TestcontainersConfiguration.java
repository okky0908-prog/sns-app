package com.okimoto.sns.backend.support;

import com.okimoto.sns.backend.storage.StorageProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * テスト用の PostgreSQL と LocalStack（S3 の代わり）を、Testcontainers の使い捨てのコンテナで立ち上げる（Issue #89）。
 *
 * <p>{@code @SpringBootTest} のテストは、すべて {@code @Import(TestcontainersConfiguration.class)}
 * を付ける。付け忘れると、 application-test.yml の「つながらない宛先」に接続しようとして失敗する（開発用の DB には書き込まない）。
 *
 * <ul>
 *   <li>開発用の環境（docker-compose.yml）と同じ版を使う。バケットも同じスクリプトで作る
 *   <li>コンテナはテストの実行全体（JVM）で1回だけ立ち上げて使い回す。Spring のコンテキストごとに立ち上げると遅いため。 テストが終わると Testcontainers
 *       が自動で消す
 *   <li>H2 は使わない。SQL が PostgreSQL 特有の書き方（ON CONFLICT・ILIKE など）を使っており、H2 では本番と同じ動きを確かめられないため
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  private static final String BUCKET = "sns-app-images";

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17")).withDatabaseName("sns");

  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
          .withServices("s3")
          .withCopyFileToContainer(
              MountableFile.forHostPath("../localstack/init/ready.d/create-bucket.sh", 0755),
              "/tmp/create-bucket.sh");

  static {
    Startables.deepStart(POSTGRES, LOCALSTACK).join();
    createBucket();
  }

  /** 開発用の LocalStack と同じスクリプトで、画像のバケットを作る */
  private static void createBucket() {
    try {
      ExecResult result = LOCALSTACK.execInContainer("bash", "/tmp/create-bucket.sh");
      if (result.getExitCode() != 0) {
        throw new IllegalStateException("バケットを作れませんでした: " + result.getStderr());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** application-test.yml の接続先（つながらない宛先）を、コンテナの接続先で上書きする */
  @Bean
  DynamicPropertyRegistrar containerProperties() {
    return registry -> {
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      registry.add("app.storage.endpoint", () -> LOCALSTACK.getEndpoint().toString());
      registry.add("app.storage.region", LOCALSTACK::getRegion);
      registry.add("app.storage.image-base-url", TestcontainersConfiguration::imageBaseUrl);
    };
  }

  /** Spring を使わないテスト（ImageStorageTest）用の、コンテナの LocalStack への接続設定 */
  public static StorageProperties storageProperties() {
    return new StorageProperties(
        BUCKET, LOCALSTACK.getRegion(), LOCALSTACK.getEndpoint().toString(), imageBaseUrl());
  }

  /** 画像を表示するときの配信元の URL（コンテナの LocalStack のバケット） */
  private static String imageBaseUrl() {
    return LOCALSTACK.getEndpoint() + "/" + BUCKET;
  }
}
