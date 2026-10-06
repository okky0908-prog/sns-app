package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

/**
 * タイムラインの続きを取るためのカーソル（docs/api.md「カーソル方式のページング」）。
 *
 * <p>画面の一番下にある投稿の「投稿日時と ID」を持ち、それより古い投稿を次に取る。ページ番号（OFFSET）と違い、
 * 新しい投稿が先頭に増えても続きの位置がずれないので、重複も抜けも起きない。画面側からは中身の決まりを気にせず扱えるよう、 文字列に変換して渡す。
 */
public record TimelineCursor(OffsetDateTime createdAt, long id) {

  static TimelineCursor of(Post post) {
    return new TimelineCursor(post.getCreatedAt(), post.getId());
  }

  /** 「秒:ナノ秒:ID」を URL で使える Base64 にする */
  public String encode() {
    Instant instant = createdAt.toInstant();
    String raw = instant.getEpochSecond() + ":" + instant.getNano() + ":" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** 画面から受け取ったカーソルを読む。形式が違えば 400 */
  public static TimelineCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split(":");
      if (parts.length != 3) {
        throw new IllegalArgumentException(raw);
      }
      Instant instant = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
      return new TimelineCursor(
          OffsetDateTime.ofInstant(instant, ZoneOffset.UTC), Long.parseLong(parts[2]));
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw ApiException.badRequest(List.of(new ApiError.FieldError("cursor", "カーソルの形式が正しくありません")));
    }
  }
}
