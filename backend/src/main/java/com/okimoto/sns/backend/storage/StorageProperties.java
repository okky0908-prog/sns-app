package com.okimoto.sns.backend.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 画像の保存先の設定（application.yml の app.storage）。docs/infrastructure.md「環境変数」も参照。
 *
 * @param bucket S3 のバケット名
 * @param region S3 のリージョン
 * @param endpoint 接続先の URL。ローカル開発では LocalStack（http://localhost:4566）。空なら AWS の S3（本番）
 * @param imageBaseUrl 画像を表示するときの配信元の URL（ローカルは LocalStack のバケットの URL、本番は CloudFront の URL）
 *     <p>endpoint 以外が空なら、起動時にエラーにする（設定し忘れたまま動き出し、最初のアップロードまで気づかないのを防ぐ）。 本番の値は
 *     application-production.yml を参照
 */
@Validated
@ConfigurationProperties("app.storage")
public record StorageProperties(
    @NotBlank String bucket,
    @NotBlank String region,
    String endpoint,
    @NotBlank String imageBaseUrl) {

  /** LocalStack など、AWS 以外の S3 互換の接続先を使うか */
  public boolean usesCustomEndpoint() {
    return endpoint != null && !endpoint.isBlank();
  }
}
