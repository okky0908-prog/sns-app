package com.okimoto.sns.backend.web;

import java.util.List;

/**
 * カーソル方式の一覧 API のレスポンス（docs/api.md「カーソル方式のページング」）。
 *
 * @param items 取得した中身（新しい順）
 * @param nextCursor 続きを取るときに {@code ?cursor=} に渡す値。続きがなければ null
 * @param hasNext 続きがあるか
 */
public record CursorPageResponse<T>(List<T> items, String nextCursor, boolean hasNext) {}
