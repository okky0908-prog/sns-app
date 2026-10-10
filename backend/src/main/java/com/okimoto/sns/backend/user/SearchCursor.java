package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * ユーザー検索（キーワードあり）の続きを取るためのカーソル。
 *
 * <p>検索結果は「一致の度合い（0：ユーザー名が完全一致、1：前方一致、2：それ以外）→ ユーザー名（小文字）」の順に並べるので、
 * 画面に出ている最後の行のこの2つを持ち、それより後のものを次に取る。ユーザー名は大文字・小文字を区別せずに重複できないので、 この2つで順番が一意に決まる。
 * キーワードが空のとき（最近参加したユーザー）は、登録日時と ID の CreatedAtCursor を使う。
 *
 * @param rank 一致の度合い
 * @param username ユーザー名（小文字）
 */
public record SearchCursor(int rank, String username) {

  /** 「一致の度合い:ユーザー名」を URL で使える Base64 にする */
  public String encode() {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((rank + ":" + username).getBytes(StandardCharsets.UTF_8));
  }

  /** 画面から受け取ったカーソルを読む。指定がなければ（最初のページ）null。形式が違えば 400 */
  public static SearchCursor decodeOrNull(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int colon = raw.indexOf(':');
      int rank = Integer.parseInt(raw.substring(0, colon));
      String username = raw.substring(colon + 1);
      if (rank < 0 || rank > 2 || username.isEmpty()) {
        throw new IllegalArgumentException(raw);
      }
      return new SearchCursor(rank, username);
    } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
      throw ApiException.badRequest(List.of(new ApiError.FieldError("cursor", "カーソルの形式が正しくありません")));
    }
  }
}
