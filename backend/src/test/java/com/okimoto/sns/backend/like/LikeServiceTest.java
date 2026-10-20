package com.okimoto.sns.backend.like;

import static com.okimoto.sns.backend.support.ApiErrors.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * LikeService の単体テスト（Mapper は偽物）。
 *
 * <ul>
 *   <li>同値分割：投稿がある・ない
 *   <li>エラー推測：投稿があることを確かめてから登録するまでの間に、投稿が削除された（外部キーの違反 → 404）
 * </ul>
 *
 * <p>いいね済みでもう一度いいねしても状態が変わらないこと（ON CONFLICT）は、DB の動きなので LikeMapperTest で確かめる。
 */
class LikeServiceTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");

  private final LikeMapper likeMapper = mock(LikeMapper.class);
  private final PostMapper postMapper = mock(PostMapper.class);
  private final LikeService service =
      new LikeService(likeMapper, postMapper, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

  @Test
  void いいねすると_今の日時で登録し_そのあとの件数といいね済みかを返す() {
    when(postMapper.existsById(10)).thenReturn(true);
    when(likeMapper.countByPostId(10)).thenReturn(3L);
    when(likeMapper.exists(10, 1)).thenReturn(true);

    LikeResponse response = service.like(1, 10);

    verify(likeMapper).insert(10, 1, NOW);
    assertThat(response).isEqualTo(new LikeResponse(3, true));
  }

  @Test
  void 取り消すと_削除し_そのあとの件数といいね済みかを返す() {
    when(postMapper.existsById(10)).thenReturn(true);
    when(likeMapper.countByPostId(10)).thenReturn(2L);
    when(likeMapper.exists(10, 1)).thenReturn(false);

    LikeResponse response = service.unlike(1, 10);

    verify(likeMapper).delete(10, 1);
    assertThat(response).isEqualTo(new LikeResponse(2, false));
  }

  @Test
  void 投稿がなければ404で_いいねも取り消しもしない() {
    when(postMapper.existsById(10)).thenReturn(false);

    assertApiError(() -> service.like(1, 10), ErrorCode.POST_NOT_FOUND);
    assertApiError(() -> service.unlike(1, 10), ErrorCode.POST_NOT_FOUND);
    verify(likeMapper, never()).insert(anyLong(), anyLong(), any());
    verify(likeMapper, never()).delete(anyLong(), anyLong());
  }

  @Test
  void 確かめてから登録するまでの間に投稿が削除されたら404() {
    when(postMapper.existsById(10)).thenReturn(true);
    doThrow(new DataIntegrityViolationException("外部キーの違反")).when(likeMapper).insert(10, 1, NOW);

    assertApiError(() -> service.like(1, 10), ErrorCode.POST_NOT_FOUND);
  }
}
