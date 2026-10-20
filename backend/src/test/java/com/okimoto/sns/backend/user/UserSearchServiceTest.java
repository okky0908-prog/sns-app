package com.okimoto.sns.backend.user;

import static com.okimoto.sns.backend.support.ApiErrors.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import com.okimoto.sns.backend.web.CursorPageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * UserSearchService の単体テスト（Mapper・S3 は偽物）。
 *
 * <ul>
 *   <li>同値分割：キーワードが空（空白だけ・{@code @} だけを含む）→ 最近参加したユーザー、それ以外 → 検索
 *   <li>境界値分析：キーワード 50・51文字（前後の空白と先頭の {@code @} を除いて、絵文字も1文字で数える）、1ページ 20・21件
 *   <li>エラー推測：LIKE の特殊文字のエスケープ（{@code \} を最初に逃がす）、先頭の {@code @} は1つだけ取り除く、ユーザー名の大文字
 * </ul>
 */
class UserSearchServiceTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");

  private final UserMapper userMapper = mock(UserMapper.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final UserSearchService service = new UserSearchService(userMapper, imageStorage);

  private static FoundUser found(long id, String username, int rank) {
    FoundUser user = new FoundUser();
    user.setId(id);
    user.setUsername(username);
    user.setDisplayName(username + "の名前");
    user.setCreatedAt(NOW.minusSeconds(id));
    user.setMatchRank(rank);
    return user;
  }

  // ===== キーワードの前処理と分岐 =====

  @ParameterizedTest(name = "キーワード「{0}」は空として、最近参加したユーザーを返す")
  @NullSource
  @ValueSource(strings = {"", "   ", "@", " @ ", "\t\n"})
  void キーワードが空なら最近参加したユーザー(String q) {
    service.search(1, q, null);

    verify(userMapper).findRecent(1, null, 21);
    verify(userMapper, never()).search(anyString(), anyString(), anyLong(), any(), anyInt());
  }

  @ParameterizedTest(name = "「{0}」は、キーワード「{1}」で探す")
  @CsvSource({"yama, yama", "'  yama  ', yama", "@yama, yama", "' @ yama ', yama", "@@yama, @yama"})
  void 前後の空白と先頭のアットマークを1つ取り除いて探す(String q, String keyword) {
    service.search(1, q, null);

    verify(userMapper).search(keyword, UserSearchService.escapeLike(keyword), 1, null, 21);
  }

  @Test
  void キーワードは50文字まで_絵文字も1文字と数え_前後の空白とアットマークは数えない() {
    service.search(1, " @" + "🎉".repeat(50) + " ", null);

    verify(userMapper).search(anyString(), anyString(), anyLong(), any(), anyInt());
  }

  @Test
  void キーワードが51文字なら400で_探さない() {
    assertFieldError(() -> service.search(1, "あ".repeat(51), null), "q", "検索キーワードは50文字以内で入力してください");
    verify(userMapper, never()).search(anyString(), anyString(), anyLong(), any(), anyInt());
  }

  @ParameterizedTest(name = "「{0}」は「{1}」にエスケープする")
  @CsvSource(
      delimiter = '|',
      value = {
        "yama | yama",
        "a_b | a\\_b",
        "100% | 100\\%",
        "\\ | \\\\",
        // \ を最初に逃がす。順番を逆にすると、% や _ のために足した \ まで二重に逃がしてしまう
        "\\_ | \\\\\\_"
      })
  void LIKEで特別な意味を持つ文字をエスケープする(String keyword, String escaped) {
    assertThat(UserSearchService.escapeLike(keyword)).isEqualTo(escaped);
  }

  // ===== ページ =====

  @ParameterizedTest(name = "検索で DB から {0} 件取れたら、続きは {1}")
  @CsvSource({"20, false", "21, true"})
  void 検索は1ページ20件で_次のカーソルは一致の度合いと小文字のユーザー名(int rows, boolean hasNext) {
    List<FoundUser> rowsFound =
        LongStream.rangeClosed(1, rows).mapToObj(id -> found(id, "Yama" + id, 1)).toList();
    when(userMapper.search("yama", "yama", 1, null, 21)).thenReturn(rowsFound);

    CursorPageResponse<UserListItemResponse> page = service.search(1, "yama", null);

    assertThat(page.items()).hasSize(20);
    assertThat(page.nextCursor())
        .isEqualTo(hasNext ? new SearchCursor(1, "yama20").encode() : null);
  }

  @Test
  void 最近参加したユーザーの次のカーソルは登録日時とID() {
    List<FoundUser> rows =
        LongStream.rangeClosed(1, 21).mapToObj(id -> found(id, "user" + id, 0)).toList();
    when(userMapper.findRecent(1, null, 21)).thenReturn(rows);

    CursorPageResponse<UserListItemResponse> page = service.search(1, "", null);

    assertThat(page.hasNext()).isTrue();
    assertThat(page.nextCursor()).isEqualTo(CreatedAtCursor.of(NOW.minusSeconds(20), 20).encode());
  }

  @Test
  void 続きは_キーワードの有無に合った形のカーソルを読み取って渡す() {
    service.search(1, "", CreatedAtCursor.of(NOW, 5).encode());
    service.search(1, "yama", new SearchCursor(2, "yamato").encode());

    verify(userMapper).findRecent(1, new CreatedAtCursor(NOW, 5), 21);
    verify(userMapper).search("yama", "yama", 1, new SearchCursor(2, "yamato"), 21);
  }

  @Test
  void 各行は_自分自身かとフォロー中かとアイコンのURLを持つ() {
    FoundUser self = found(1, "alice", 0);
    FoundUser other = found(2, "alicia", 1);
    other.setFollowedByMe(true);
    other.setIconKey("icons/2.png");
    when(imageStorage.urlOf("icons/2.png")).thenReturn("https://cdn/icons/2.png");
    when(userMapper.search("ali", "ali", 1, null, 21)).thenReturn(List.of(self, other));

    List<UserListItemResponse> items = service.search(1, "ali", null).items();

    assertThat(items.get(0).me()).isTrue();
    assertThat(items.get(1).me()).isFalse();
    assertThat(items.get(1).followedByMe()).isTrue();
    assertThat(items.get(1).iconUrl()).isEqualTo("https://cdn/icons/2.png");
  }
}
