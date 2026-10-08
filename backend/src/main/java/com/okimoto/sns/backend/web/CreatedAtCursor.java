package com.okimoto.sns.backend.web;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

/**
 * 一覧の続きを取るためのカーソル（docs/api.md「カーソル方式のページング」）。タイムライン・コメント一覧で共通。
 *
 * <p>画面に出ている一番最後の行の「作成日時と ID」を持ち、次はその続き（タイムラインならそれより古いもの、コメントならそれより新しいもの）を取る。
 * ページ番号（OFFSET）と違い、途中で行が増えても続きの位置がずれないので、重複も抜けも起きない。画面側からは中身の決まりを気にせず扱えるよう、 文字列に変換して渡す。
 */
public record CreatedAtCursor(OffsetDateTime createdAt, long id) {

  public static CreatedAtCursor of(OffsetDateTime createdAt, long id) {
    return new CreatedAtCursor(createdAt, id);
  }

  /** 「秒:ナノ秒:ID」を URL で使える Base64 にする */
  public String encode() {
    Instant instant = createdAt.toInstant();
    String raw = instant.getEpochSecond() + ":" + instant.getNano() + ":" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** 画面から受け取ったカーソルを読む。指定がなければ（最初のページ）null。形式が違えば 400 */
  public static CreatedAtCursor decodeOrNull(String cursor) {
    return cursor == null || cursor.isBlank() ? null : decode(cursor);
  }

  /** 画面から受け取ったカーソルを読む。形式が違えば 400 */
  public static CreatedAtCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split(":");
      if (parts.length != 3) {
        throw new IllegalArgumentException(raw);
      }
      Instant instant = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
      return new CreatedAtCursor(
          OffsetDateTime.ofInstant(instant, ZoneOffset.UTC), Long.parseLong(parts[2]));
    } catch (IllegalArgumentException | DateTimeException e) {
      throw ApiException.badRequest(List.of(new ApiError.FieldError("cursor", "カーソルの形式が正しくありません")));
    }
  }
}
