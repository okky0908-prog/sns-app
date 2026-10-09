package com.okimoto.sns.backend.storage;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 への接続（docs/infrastructure.md）。
 *
 * <ul>
 *   <li>ローカル開発（接続先の URL があるとき）：LocalStack に接続する。LocalStack は認証情報を確かめないので、ダミーの値を使う。 URL
 *       はバケット名をパスに入れる形（http://localhost:4566/バケット名/キー）にする
 *   <li>本番（接続先の URL が空）：AWS の S3 に接続する。認証情報は EC2 の IAM ロールから自動で取得する（アクセスキーをサーバーに置かない）
 * </ul>
 */
@Configuration
public class S3Config {

  @Bean
  S3Client s3Client(StorageProperties properties) {
    var builder = S3Client.builder().region(Region.of(properties.region()));
    if (properties.usesCustomEndpoint()) {
      return builder
          .endpointOverride(URI.create(properties.endpoint()))
          .forcePathStyle(true)
          .credentialsProvider(
              StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
          .build();
    }
    return builder.credentialsProvider(DefaultCredentialsProvider.builder().build()).build();
  }
}
