package com.okimoto.sns.backend.storage;

import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.io.IOException;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * アップロードされた画像（形式と大きさを確かめたもの）。プロフィールのアイコンと投稿の画像で共通。
 *
 * <p>形式はファイル名や Content-Type ではなく、中身の先頭のバイト列で判定する（{@link ImageType}）。
 *
 * @param bytes 画像の中身
 * @param type 中身から判定した形式
 */
public record UploadedImage(byte[] bytes, ImageType type) {

  /** 1枚の上限（5MB） */
  public static final long MAX_BYTES = 5L * 1024 * 1024;

  public static final String TYPE_ERROR = "jpg・png・gif の画像を選択してください";
  public static final String SIZE_ERROR = "5MB以下の画像を選択してください";

  /**
   * 形式と大きさを確かめる。違反なら、その項目（field）の入力エラー（400）にする。
   *
   * @param field エラーで返す項目名（例：icon、images）
   */
  public static UploadedImage from(MultipartFile file, String field) {
    if (file.getSize() > MAX_BYTES) {
      throw fieldError(field, SIZE_ERROR);
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ApiException(ErrorCode.MALFORMED_REQUEST);
    }
    ImageType type = ImageType.detect(bytes).orElseThrow(() -> fieldError(field, TYPE_ERROR));
    return new UploadedImage(bytes, type);
  }

  private static ApiException fieldError(String field, String message) {
    return ApiException.badRequest(List.of(new ApiError.FieldError(field, message)));
  }
}
