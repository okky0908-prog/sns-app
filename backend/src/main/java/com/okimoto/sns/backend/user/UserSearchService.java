package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import com.okimoto.sns.backend.web.CursorPageResponse;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ユーザー検索と、最近参加したユーザーの一覧（docs/feature-specs/08_user_search.md の F-70・F-71）。 */
@Service
public class UserSearchService {

  static final int PAGE_SIZE = 20;
  static final int KEYWORD_MAX = 50;

  private final UserMapper userMapper;
  private final ImageStorage imageStorage;

  public UserSearchService(UserMapper userMapper, ImageStorage imageStorage) {
    this.userMapper = userMapper;
    this.imageStorage = imageStorage;
  }

  /**
   * A-70。キーワードは前後の空白と先頭の @ を1つ取り除いてから使う（@yama と yama は同じ結果）。 取り除いた結果が空なら、最近参加したユーザーを返す。
   *
   * @param q 検索キーワード（50文字まで）。null・空なら最近参加したユーザー
   * @param cursor 前回のレスポンスの nextCursor。キーワードの有無で中身の形式が違う
   */
  @Transactional(readOnly = true)
  public CursorPageResponse<UserListItemResponse> search(long me, String q, String cursor) {
    String keyword = normalize(q);
    if (keyword.codePointCount(0, keyword.length()) > KEYWORD_MAX) {
      throw ApiException.badRequest(
          List.of(new ApiError.FieldError("q", "検索キーワードは50文字以内で入力してください")));
    }
    if (keyword.isEmpty()) {
      List<FoundUser> rows =
          userMapper.findRecent(me, CreatedAtCursor.decodeOrNull(cursor), PAGE_SIZE + 1);
      return toPage(
          me, rows, last -> CreatedAtCursor.of(last.getCreatedAt(), last.getId()).encode());
    }
    List<FoundUser> rows =
        userMapper.search(
            keyword, escapeLike(keyword), me, SearchCursor.decodeOrNull(cursor), PAGE_SIZE + 1);
    return toPage(
        me,
        rows,
        last ->
            new SearchCursor(last.getMatchRank(), last.getUsername().toLowerCase(Locale.ROOT))
                .encode());
  }

  /** 前後の空白と、先頭の @ を1つ取り除く */
  static String normalize(String q) {
    String keyword = q == null ? "" : q.strip();
    return keyword.startsWith("@") ? keyword.substring(1).strip() : keyword;
  }

  /**
   * LIKE で特別な意味を持つ文字（% _ \）を、普通の文字として探せるようにエスケープする。 エスケープしないと、「_」が「任意の1文字」として扱われ、関係ないユーザーまでヒットしてしまう
   */
  static String escapeLike(String keyword) {
    return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** PAGE_SIZE + 1 件取った結果を、1ページ分と「続きがあるか」に分ける（タイムラインと同じやり方） */
  private CursorPageResponse<UserListItemResponse> toPage(
      long me, List<FoundUser> rows, Function<FoundUser, String> cursorOf) {
    boolean hasNext = rows.size() > PAGE_SIZE;
    List<FoundUser> page = rows.subList(0, Math.min(PAGE_SIZE, rows.size()));
    String nextCursor = hasNext ? cursorOf.apply(page.getLast()) : null;
    List<UserListItemResponse> items =
        page.stream()
            .map(
                row ->
                    UserListItemResponse.of(
                        row.getId(),
                        row.getUsername(),
                        row.getDisplayName(),
                        row.getBio(),
                        imageStorage.urlOf(row.getIconKey()),
                        row.isFollowedByMe(),
                        me))
            .toList();
    return new CursorPageResponse<>(items, nextCursor, hasNext);
  }
}
