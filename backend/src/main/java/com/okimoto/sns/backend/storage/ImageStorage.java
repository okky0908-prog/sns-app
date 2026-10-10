package com.okimoto.sns.backend.storage;

import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

  /**
   * 画像を prefix の下に「UUID.拡張子」の名前で保存し、そのキーを返す（元のファイル名は使わない）。
   *
   * <p>保存できなければ 500（IMAGE_UPLOAD_FAILED）にする。
   *
   * @param prefix 保存先のフォルダ（例：icons/、posts/2026/10/）。末尾は /
   */
  public String store(String prefix, UploadedImage image) {
    String key = prefix + UUID.randomUUID() + "." + image.type().extension();
    try {
      upload(key, image.bytes(), image.type().contentType());
    } catch (SdkException e) {
      log.error("画像を保存できませんでした: {}", key, e);
      throw new ApiException(ErrorCode.IMAGE_UPLOAD_FAILED);
    }
    return key;
  }

  /**
   * 今のトランザクションが終わったときに、使わなくなった画像を削除する（削除は失敗してもログだけ）。
   *
   * <p>DB の更新より先に S3 に保存し、古い画像は DB のコミット後に消す、という順番を守るために使う（docs/database.md「参照整合性・カスケード削除について」）。
   *
   * @param onCommit コミットされたら削除する画像（差し替え前の画像、削除した投稿の画像など）
   * @param onRollback 取り消されたら削除する画像（保存したばかりで、どこからも使われなくなる画像）
   */
  public void deleteAfterTransaction(List<String> onCommit, List<String> onRollback) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            (status == STATUS_COMMITTED ? onCommit : onRollback).forEach(key -> deleteQuietly(key));
          }
        });
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
