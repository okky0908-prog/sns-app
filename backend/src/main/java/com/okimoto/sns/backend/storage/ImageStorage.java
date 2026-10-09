package com.okimoto.sns.backend.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 画像ファイルの保存・削除と、表示用の URL の組み立て（docs/api.md「画像 URL の組み立て」）。
 *
 * <p>DB には S3 のキー（例：icons/uuid.png）だけを保存し、画面に返すときに「配信元の URL + キー」で URL を作る。 配信元（LocalStack /
 * CloudFront）が変わっても DB を書き換えずに済むようにするため。
 */
@Component
public class ImageStorage {

  private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);

  private final S3Client s3Client;
  private final StorageProperties properties;

  public ImageStorage(S3Client s3Client, StorageProperties properties) {
    this.s3Client = s3Client;
    this.properties = properties;
  }

  /**
   * 画像を保存する。失敗したら例外（SdkException）をそのまま投げる（呼ぶ側で、DB の更新も取り消してエラーにする）。
   *
   * @param key 保存先のキー（例：icons/uuid.png）
   * @param contentType 画像の形式（例：image/png）。ブラウザが画像として表示するために付ける
   */
  public void upload(String key, byte[] bytes, String contentType) {
    s3Client.putObject(
        request -> request.bucket(properties.bucket()).key(key).contentType(contentType),
        RequestBody.fromBytes(bytes));
  }

  /**
   * 画像を削除する。差し替え前の古い画像を消すときなど、DB の更新が終わったあとに呼ぶ。
   *
   * <p>削除に失敗しても、利用者の操作（保存）は成功しているので、エラーにはせずログに残すだけにする（docs/database.md「参照整合性・カスケード削除について」）。
   */
  public void deleteQuietly(String key) {
    try {
      s3Client.deleteObject(request -> request.bucket(properties.bucket()).key(key));
    } catch (SdkException e) {
      log.warn("画像を削除できませんでした（S3 に残っています）: {}", key, e);
    }
  }

  /** 表示用の URL。キーがなければ（画像を設定していなければ）null */
  public String urlOf(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    String base = properties.imageBaseUrl();
    return (base.endsWith("/") ? base : base + "/") + key;
  }
}
