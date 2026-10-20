package com.okimoto.sns.backend.comment;

import static com.okimoto.sns.backend.support.ApiErrors.assertApiError;
import static com.okimoto.sns.backend.support.ApiErrors.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * CommentService の単体テスト（Mapper・S3 は偽物）。
 *
 * <ul>
 *   <li>デシジョンテーブル（削除）：コメントがない → 404、他人のコメント → 403、自分のコメント → 削除
 *   <li>境界値分析（一覧）：1ページ20件。DB から 21件取れたら続きあり、20件以下なら続きなし。次のカーソルは20件目から作る
 *   <li>エラー推測：投稿がない、確かめてから登録するまでの間に投稿が削除された、本文の前後の空白、形の違うカーソル
 * </ul>
 */
class CommentServiceTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");

  private final CommentMapper commentMapper = mock(CommentMapper.class);
  private final PostMapper postMapper = mock(PostMapper.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final CommentService service =
      new CommentService(
          commentMapper, postMapper, imageStorage, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

  private static Comment comment(long id, long postId, long userId, OffsetDateTime createdAt) {
    Comment comment = new Comment(postId, userId, "コメント" + id, createdAt);
    comment.setId(id);
    comment.setAuthorUsername("user" + userId);
    comment.setAuthorDisplayName("名前" + userId);
    return comment;
  }

  // ===== 一覧 =====

  @ParameterizedTest(name = "DB から {0} 件取れたら、{1} 件を返し、続きは {2}")
  @CsvSource({"0, 0, false", "20, 20, false", "21, 20, true"})
  void 一覧は1ページ20件で_21件目があれば続きあり(int rows, int returned, boolean hasNext) {
    when(postMapper.existsById(10)).thenReturn(true);
    List<Comment> found =
        LongStream.rangeClosed(1, rows)
            .mapToObj(id -> comment(id, 10, 2, NOW.plusSeconds(id)))
            .toList();
    when(commentMapper.findByPostId(10, null, 21)).thenReturn(found);

    CursorPageResponse<CommentResponse> page = service.list(1, 10, null);

    assertThat(page.items()).hasSize(returned);
    assertThat(page.hasNext()).isEqualTo(hasNext);
    if (hasNext) {
      // 次のカーソルは、返した最後の行（20件目）から作る
      assertThat(page.nextCursor()).isEqualTo(CreatedAtCursor.of(NOW.plusSeconds(20), 20).encode());
    } else {
      assertThat(page.nextCursor()).isNull();
    }
  }

  @Test
  void 一覧の各行は_自分のコメントかと書いた人のアイコンのURLを持つ() {
    when(postMapper.existsById(10)).thenReturn(true);
    Comment mine = comment(1, 10, 1, NOW);
    mine.setAuthorIconKey("icons/me.png");
    when(commentMapper.findByPostId(10, null, 21))
        .thenReturn(List.of(mine, comment(2, 10, 2, NOW)));
    when(imageStorage.urlOf("icons/me.png")).thenReturn("https://cdn/icons/me.png");

    List<CommentResponse> items = service.list(1, 10, null).items();

    assertThat(items.get(0).mine()).isTrue();
    assertThat(items.get(0).author().iconUrl()).isEqualTo("https://cdn/icons/me.png");
    assertThat(items.get(1).mine()).isFalse();
  }

  @Test
  void 続きはカーソルを読み取ってMapperに渡す() {
    when(postMapper.existsById(10)).thenReturn(true);
    String cursor = CreatedAtCursor.of(NOW, 5).encode();

    service.list(1, 10, cursor);

    verify(commentMapper).findByPostId(10, new CreatedAtCursor(NOW, 5), 21);
  }

  @Test
  void 形の違うカーソルは400() {
    assertFieldError(() -> service.list(1, 10, "壊れたカーソル"), "cursor", "カーソルの形式が正しくありません");
  }

  @Test
  void 投稿がなければ一覧も投稿も404() {
    when(postMapper.existsById(10)).thenReturn(false);

    assertApiError(() -> service.list(1, 10, null), ErrorCode.POST_NOT_FOUND);
    assertApiError(() -> service.create(1, 10, "コメント"), ErrorCode.POST_NOT_FOUND);
    verify(commentMapper, never()).findByPostId(anyLong(), any(), anyInt());
    verify(commentMapper, never()).insert(any());
  }

  // ===== 投稿 =====

  @Test
  void 投稿すると_前後の空白を除いて今の日時で登録し_投稿後のコメント数を返す() {
    when(postMapper.existsById(10)).thenReturn(true);
    doAnswer(
            invocation -> {
              invocation.<Comment>getArgument(0).setId(99L);
              return null;
            })
        .when(commentMapper)
        .insert(any());
    when(commentMapper.findById(99)).thenReturn(Optional.of(comment(99, 10, 1, NOW)));
    when(commentMapper.countByPostId(10)).thenReturn(4L);

    CreatedCommentResponse created = service.create(1, 10, "  わかりやすいです！\n ");

    verify(commentMapper)
        .insert(
            argThat(
                c ->
                    c.getContent().equals("わかりやすいです！")
                        && c.getPostId() == 10
                        && c.getUserId() == 1
                        && c.getCreatedAt().equals(NOW)));
    assertThat(created.id()).isEqualTo(99);
    assertThat(created.mine()).isTrue();
    assertThat(created.commentCount()).isEqualTo(4);
  }

  @Test
  void 確かめてから登録するまでの間に投稿が削除されたら404() {
    when(postMapper.existsById(10)).thenReturn(true);
    doThrow(new DataIntegrityViolationException("外部キーの違反")).when(commentMapper).insert(any());

    assertApiError(() -> service.create(1, 10, "コメント"), ErrorCode.POST_NOT_FOUND);
  }

  // ===== 削除（デシジョンテーブル） =====

  @Test
  void ないコメントの削除は404() {
    when(commentMapper.findById(5)).thenReturn(Optional.empty());

    assertApiError(() -> service.delete(1, 5), ErrorCode.COMMENT_NOT_FOUND);
    verify(commentMapper, never()).delete(anyLong());
  }

  @Test
  void 他人のコメントの削除は403で_消さない() {
    when(commentMapper.findById(5)).thenReturn(Optional.of(comment(5, 10, 2, NOW)));

    assertApiError(() -> service.delete(1, 5), ErrorCode.FORBIDDEN);
    verify(commentMapper, never()).delete(anyLong());
  }

  @Test
  void 自分のコメントは削除でき_削除後のコメント数を返す() {
    when(commentMapper.findById(5)).thenReturn(Optional.of(comment(5, 10, 1, NOW)));
    when(commentMapper.countByPostId(10)).thenReturn(3L);

    CommentCountResponse response = service.delete(1, 5);

    verify(commentMapper).delete(5);
    assertThat(response.commentCount()).isEqualTo(3);
  }
}
