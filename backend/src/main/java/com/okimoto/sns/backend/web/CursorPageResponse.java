package com.okimoto.sns.backend.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;

/**
 * カーソル方式の一覧 API のレスポンス（docs/api.md「カーソル方式のページング」）。
 *
 * @param items 取得した中身（新しい順）
 * @param nextCursor 続きを取るときに {@code ?cursor=} に渡す値。続きがなければ null
 * @param hasNext 続きがあるか
 */
public record CursorPageResponse<T>(
    List<T> items, @Schema(nullable = true) String nextCursor, boolean hasNext) {

  /**
   * 1ページの件数より1件多く取った結果（rows）を、1ページ分と「続きがあるか」に分けてレスポンスにする。 余分の1件があれば「続きあり」とする（件数を数える SQL
   * を別に発行しないため）。タイムライン・コメント・フォロー一覧・ユーザー検索で共通
   *
   * @param rows SQL で pageSize + 1 件まで取った結果
   * @param pageSize 1ページの件数
   * @param cursorOf 1ページ分の最後の行から、続きを取るためのカーソルを作る
   * @param toItems 1ページ分の行をレスポンスの中身にする（画像のように、1ページ分まとめて取るものがあるので、行ごとではなくリストで受け取る）
   */
  public static <R, T> CursorPageResponse<T> of(
      List<R> rows,
      int pageSize,
      Function<R, String> cursorOf,
      Function<List<R>, List<T>> toItems) {
    boolean hasNext = rows.size() > pageSize;
    List<R> page = rows.subList(0, Math.min(pageSize, rows.size()));
    String nextCursor = hasNext ? cursorOf.apply(page.getLast()) : null;
    return new CursorPageResponse<>(toItems.apply(page), nextCursor, hasNext);
  }
}
