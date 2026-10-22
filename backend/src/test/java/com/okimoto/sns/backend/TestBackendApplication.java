package com.okimoto.sns.backend;

import com.okimoto.sns.backend.support.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * E2E テスト（frontend/e2e）用に、バックエンドを使い捨てのコンテナ（PostgreSQL・LocalStack）につないで起動する。
 *
 * <p>{@code ./gradlew bootTestRun} で起動する。テストと同じ {@link TestcontainersConfiguration} と
 * application-test.yml を使うので、開発用の DB（docker-compose）には書き込まない。止めるとコンテナも消え、データは残らない
 */
public class TestBackendApplication {

  public static void main(String[] args) {
    SpringApplication.from(BackendApplication::main)
        .with(TestcontainersConfiguration.class)
        .withAdditionalProfiles("test")
        .run(args);
  }
}
