package com.okimoto.sns.backend.web;

import java.util.List;

/**
 * 一覧 API のレスポンスの共通形式（docs/api.md「ページングのレスポンス」）。
 *
 * @param items そのページの中身
 * @param page ページ番号（0 から）
 * @param hasNext 次のページがあるか（画面の「もっと見る」を出すかどうか）
 */
public record PageResponse<T>(List<T> items, int page, boolean hasNext) {}
