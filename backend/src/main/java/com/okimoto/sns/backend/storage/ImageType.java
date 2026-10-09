package com.okimoto.sns.backend.storage;

import java.util.Arrays;
import java.util.Optional;

/**
 * 受け付ける画像の形式（jpg / png / gif）。
 *
 * <p>形式はファイル名の拡張子や送られてきた Content-Type ではなく、ファイルの中身の先頭のバイト列（マジックナンバー）で判定する。 拡張子だけ .png
 * にした別のファイル（スクリプトなど）を画像として保存・配信しないようにするため。
 */
public enum ImageType {
  JPEG("jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
  PNG("png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}),
  GIF("gif", "image/gif", new byte[] {'G', 'I', 'F', '8'});

  private final String extension;
  private final String contentType;
  private final byte[] signature;

  ImageType(String extension, String contentType, byte[] signature) {
    this.extension = extension;
    this.contentType = contentType;
    this.signature = signature;
  }

  /** 保存するファイルの拡張子（例：png） */
  public String extension() {
    return extension;
  }

  /** 保存するときに付ける形式（例：image/png）。ブラウザが画像として表示するために使う */
  public String contentType() {
    return contentType;
  }

  /** ファイルの中身から形式を判定する。jpg / png / gif のどれでもなければ空 */
  public static Optional<ImageType> detect(byte[] bytes) {
    return Arrays.stream(values())
        .filter(
            type ->
                bytes.length >= type.signature.length
                    && Arrays.equals(Arrays.copyOf(bytes, type.signature.length), type.signature))
        .findFirst();
  }
}
