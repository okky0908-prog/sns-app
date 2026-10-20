package com.okimoto.sns.backend.follow;

import static com.okimoto.sns.backend.support.ApiErrors.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.user.UserListItemResponse;
import com.okimoto.sns.backend.user.UserService;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * FollowService の単体テスト（Mapper・UserService・S3 は偽物）。
 *
 * <ul>
 *   <li>同値分割（フォロー）：自分自身 → 400、相手がいない → 404、それ以外 → 登録
 *   <li>境界値分析（一覧）：1ページ20件。21件取れたら続きあり。次のカーソルは20件目のフォロー日時と follows の ID
 *   <li>エラー推測：確かめてから登録するまでの間に相手が削除された
 * </ul>
 */
class FollowServiceTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");

  private final FollowMapper followMapper = mock(FollowMapper.class);
  private final UserService userService = mock(UserService.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final FollowService service =
      new FollowService(
          followMapper, userService, imageStorage, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

  private static FollowedUser row(long followId, long userId) {
    FollowedUser row = new FollowedUser();
    row.setFollowId(followId);
    row.setFollowedAt(NOW.minusSeconds(followId));
    row.setId(userId);
    row.setUsername("user" + userId);
    row.setDisplayName("名前" + userId);
    return row;
  }

  // ===== フォロー・解除 =====

  @Test
  void フォローすると_今の日時で登録し_そのあとの状態と相手のフォロワー数を返す() {
    when(userService.findIdOrThrow("bobby")).thenReturn(2L);
    when(followMapper.exists(1, 2)).thenReturn(true);
    when(followMapper.countFollowers(2)).thenReturn(9L);

    FollowResponse response = service.follow(1, "bobby");

    verify(followMapper).insert(1, 2, NOW);
    assertThat(response).isEqualTo(new FollowResponse(true, 9));
  }

  @Test
  void 自分自身はフォローできず400で_登録しない() {
    when(userService.findIdOrThrow("alice")).thenReturn(1L);

    assertApiError(() -> service.follow(1, "alice"), ErrorCode.CANNOT_FOLLOW_SELF);
    verify(followMapper, never()).insert(anyLong(), anyLong(), any());
  }

  @Test
  void 相手がいなければ404() {
    when(userService.findIdOrThrow("nobody")).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND));

    assertApiError(() -> service.follow(1, "nobody"), ErrorCode.USER_NOT_FOUND);
    assertApiError(() -> service.unfollow(1, "nobody"), ErrorCode.USER_NOT_FOUND);
    assertApiError(() -> service.following(1, "nobody", null), ErrorCode.USER_NOT_FOUND);
    assertApiError(() -> service.followers(1, "nobody", null), ErrorCode.USER_NOT_FOUND);
  }

  @Test
  void 確かめてから登録するまでの間に相手が削除されたら404() {
    when(userService.findIdOrThrow("bobby")).thenReturn(2L);
    doThrow(new DataIntegrityViolationException("外部キーの違反")).when(followMapper).insert(1, 2, NOW);

    assertApiError(() -> service.follow(1, "bobby"), ErrorCode.USER_NOT_FOUND);
  }

  @Test
  void 解除すると_削除し_そのあとの状態を返す() {
    when(userService.findIdOrThrow("bobby")).thenReturn(2L);
    when(followMapper.exists(1, 2)).thenReturn(false);
    when(followMapper.countFollowers(2)).thenReturn(8L);

    FollowResponse response = service.unfollow(1, "bobby");

    verify(followMapper).delete(1, 2);
    assertThat(response).isEqualTo(new FollowResponse(false, 8));
  }

  // ===== 一覧 =====

  @ParameterizedTest(name = "DB から {0} 件取れたら、{1} 件を返し、続きは {2}")
  @CsvSource({"0, 0, false", "20, 20, false", "21, 20, true"})
  void フォロー中一覧は1ページ20件で_21件目があれば続きあり(int rows, int returned, boolean hasNext) {
    when(userService.findIdOrThrow("alice")).thenReturn(1L);
    List<FollowedUser> found =
        LongStream.rangeClosed(1, rows).mapToObj(id -> row(id, 100 + id)).toList();
    when(followMapper.findFollowing(1, 3, null, 21)).thenReturn(found);

    CursorPageResponse<UserListItemResponse> page = service.following(3, "alice", null);

    assertThat(page.items()).hasSize(returned);
    assertThat(page.hasNext()).isEqualTo(hasNext);
    assertThat(page.nextCursor())
        .isEqualTo(hasNext ? CreatedAtCursor.of(NOW.minusSeconds(20), 20).encode() : null);
  }

  @Test
  void フォロワー一覧は続きのカーソルを読み取り_各行は見ている人から見た値を持つ() {
    when(userService.findIdOrThrow("alice")).thenReturn(1L);
    FollowedUser self = row(1, 3); // 見ている人（me = 3）自身の行
    FollowedUser followed = row(2, 4);
    followed.setFollowedByMe(true);
    followed.setIconKey("icons/4.png");
    when(imageStorage.urlOf("icons/4.png")).thenReturn("https://cdn/icons/4.png");
    String cursor = CreatedAtCursor.of(NOW, 50).encode();
    when(followMapper.findFollowers(1, 3, new CreatedAtCursor(NOW, 50), 21))
        .thenReturn(List.of(self, followed));

    List<UserListItemResponse> items = service.followers(3, "alice", cursor).items();

    assertThat(items.get(0).me()).isTrue();
    assertThat(items.get(1).me()).isFalse();
    assertThat(items.get(1).followedByMe()).isTrue();
    assertThat(items.get(1).iconUrl()).isEqualTo("https://cdn/icons/4.png");
  }
}
