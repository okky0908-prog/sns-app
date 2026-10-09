package com.okimoto.sns.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 画像の保存・削除・URL の組み立てのテスト。docker compose の LocalStack（http://localhost:4566、バケット sns-app-images）に
 * 実際に保存して確かめるので、テストの前に {@code docker compose up -d localstack} で起動しておくこと。
 *
 * <p>キーにはテストごとに UUID を付け、最後に削除するので、バケットにファイルは残らない。
 */
class ImageStorageTest {

  private static final StorageProperties LOCALSTACK =
      new StorageProperties(
          "sns-app-images",
          "ap-northeast-1",
          "http://localhost:4566",
          "http://localhost:4566/sns-app-images");

  private final S3Client s3Client = new S3Config().s3Client(LOCALSTACK);
  private final ImageStorage storage = new ImageStorage(s3Client, LOCALSTACK);
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<byte[]> get(String url) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void 保存した画像を表示用のURLで取得でき_削除すると取得できなくなる() throws Exception {
    String key = "test/" + UUID.randomUUID() + ".png";
    byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    storage.upload(key, bytes, "image/png");
    String url = storage.urlOf(key);
    assertThat(url).isEqualTo("http://localhost:4566/sns-app-images/" + key);

    HttpResponse<byte[]> response = get(url);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(bytes);
    // ブラウザが画像として表示できるよう、保存時の形式が付いている
    assertThat(response.headers().firstValue("Content-Type")).hasValue("image/png");

    storage.deleteQuietly(key);
    assertThat(get(url).statusCode()).isEqualTo(404);
  }

  @Test
  void 存在しない画像を削除してもエラーにならない() {
    assertThatCode(() -> storage.deleteQuietly("test/" + UUID.randomUUID() + ".png"))
        .doesNotThrowAnyException();
  }

  @Test
  void S3につながらなくても削除はエラーにせずログに残すだけ() {
    StorageProperties unreachable =
        new StorageProperties("sns-app-images", "ap-northeast-1", "http://localhost:1", "x");
    ImageStorage broken = new ImageStorage(new S3Config().s3Client(unreachable), unreachable);
    assertThatCode(() -> broken.deleteQuietly("icons/a.png")).doesNotThrowAnyException();
  }

  @Test
  void URLは配信元の末尾のスラッシュの有無によらず正しくつなぎ_キーがなければnull() {
    StorageProperties withSlash =
        new StorageProperties("b", "ap-northeast-1", "", "https://dxxxx.cloudfront.net/");
    ImageStorage cloudFront = new ImageStorage(s3Client, withSlash);
    assertThat(cloudFront.urlOf("icons/a.png"))
        .isEqualTo("https://dxxxx.cloudfront.net/icons/a.png");
    assertThat(storage.urlOf(null)).isNull();
    assertThat(storage.urlOf("")).isNull();
  }
}
