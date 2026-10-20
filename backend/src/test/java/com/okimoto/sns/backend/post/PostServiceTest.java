package com.okimoto.sns.backend.post;

import static com.okimoto.sns.backend.support.ApiErrors.assertApiError;
import static com.okimoto.sns.backend.support.ApiErrors.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.storage.UploadedImage;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * PostService の単体テスト（Mapper・S3 は偽物）。テスト計画書の 4.2・4.3・4.6 をそのままテストにする。
 *
 * <ul>
 *   <li>デシジョンテーブル：投稿の作成（4.2）・編集（4.3）・削除
 *   <li>分岐網羅（4.6）：本文が null・画像が null・空のファイル・保存の途中で失敗・画像の有無
 *   <li>境界値分析：本文 280・281文字（絵文字も1文字、{@code \r\n} も1文字）、画像 4・5枚、一覧 20・21件
 * </ul>
 */
class PostServiceTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0};
  private static final long ME = 1;

  private final PostMapper postMapper = mock(PostMapper.class);
  private final PostImageMapper postImageMapper = mock(PostImageMapper.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final PostService service =
      new PostService(
          postMapper, postImageMapper, imageStorage, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

  private static Post post(long id, long userId, String content) {
    Post post = new Post(userId, content, NOW.minusSeconds(id));
    post.setId(id);
    post.setAuthorUsername("user" + userId);
    post.setAuthorDisplayName("名前" + userId);
    return post;
  }

  private static MultipartFile png() {
    return new MockMultipartFile("images", "a.png", "image/png", PNG);
  }

  private static List<MultipartFile> pngs(int count) {
    return Collections.nCopies(count, png());
  }

  @Nested
  class 作成 {

    @BeforeEach
    void setUp() {
      // 登録すると ID 50 が振られ、そのあとの取得でその投稿を返す
      doAnswer(
              invocation -> {
                invocation.<Post>getArgument(0).setId(50L);
                return null;
              })
          .when(postMapper)
          .insert(any());
      when(postMapper.findById(50, ME)).thenReturn(Optional.of(post(50, ME, "保存した投稿")));
      AtomicInteger counter = new AtomicInteger();
      when(imageStorage.store(anyString(), any(UploadedImage.class)))
          .thenAnswer(invocation -> invocation.getArgument(0) + "img" + counter.incrementAndGet());
    }

    // ----- デシジョンテーブル（4.2） -----

    @Test
    void ケース1_本文だけなら作成し_画像の行は登録しない() {
      service.create(ME, "本文", null);

      verify(postMapper).insert(argThat(p -> p.getContent().equals("本文") && p.getUserId() == ME));
      verify(postImageMapper, never()).insertAll(any());
      verify(imageStorage).deleteAfterTransaction(List.of(), List.of());
    }

    @Test
    void ケース2_画像だけなら本文は空で作成する() {
      service.create(ME, null, List.of(png()));

      verify(postMapper).insert(argThat(p -> p.getContent().isEmpty()));
      verify(postImageMapper).insertAll(any());
    }

    @Test
    void ケース3_本文と画像なら_画像は選んだ順に表示順1から登録し_取り消したら消す() {
      service.create(ME, "本文", pngs(2));

      verify(postImageMapper)
          .insertAll(
              argThat(
                  rows ->
                      rows.stream()
                          .map(r -> r.getPostId() + ":" + r.getImageKey() + ":" + r.getSortOrder())
                          .toList()
                          .equals(List.of("50:posts/2026/10/img1:1", "50:posts/2026/10/img2:2"))));
      // 投稿の保存が取り消されたら、保存した画像を消す
      verify(imageStorage)
          .deleteAfterTransaction(List.of(), List.of("posts/2026/10/img1", "posts/2026/10/img2"));
    }

    @Test
    void ケース4_本文が281文字なら400() {
      assertFieldError(
          () -> service.create(ME, "あ".repeat(281), null), "content", PostService.CONTENT_TOO_LONG);
      verify(postMapper, never()).insert(any());
    }

    @Test
    void ケース5_本文も画像もなければ400() {
      assertFieldError(
          () -> service.create(ME, null, null), "content", PostService.CONTENT_OR_IMAGE_REQUIRED);
    }

    @Test
    void ケース6_画像が5枚なら400で_S3に保存しない() {
      assertFieldError(
          () -> service.create(ME, "本文", pngs(5)), "images", PostService.TOO_MANY_IMAGES);
      verify(imageStorage, never()).store(anyString(), any());
    }

    // ----- 境界値・同値分割 -----

    @Test
    void 画像は4枚ちょうどまで作成できる() {
      service.create(ME, null, pngs(4));

      verify(postImageMapper).insertAll(argThat(rows -> rows.size() == 4));
    }

    @Test
    void 本文は280文字まで_絵文字も1文字と数え_前後の空白は数えない() {
      service.create(ME, "  " + "🎉".repeat(280) + "\n", null);

      verify(postMapper).insert(argThat(p -> p.getContent().equals("🎉".repeat(280))));
    }

    @Test
    void ブラウザが送る改行の_CRLF_はLFにそろえて1文字と数える() {
      String body = "あ".repeat(139) + "\r\n" + "あ".repeat(140); // \r\n を1文字と数えると280文字

      service.create(ME, body, null);

      verify(postMapper)
          .insert(argThat(p -> p.getContent().equals("あ".repeat(139) + "\n" + "あ".repeat(140))));
    }

    @Test
    void 空白だけの本文と空のファイルは_どちらもないとして400() {
      MultipartFile empty = new MockMultipartFile("images", "a.png", "image/png", new byte[0]);

      assertFieldError(
          () -> service.create(ME, " \n ", List.of(empty)),
          "content",
          PostService.CONTENT_OR_IMAGE_REQUIRED);
    }

    @Test
    void 空のファイルは数えない_空のファイルを含めて5つでも中身のある画像が4枚なら作成できる() {
      List<MultipartFile> files = new ArrayList<>(pngs(4));
      files.add(new MockMultipartFile("images", "empty.png", "image/png", new byte[0]));

      service.create(ME, null, files);

      verify(imageStorage, org.mockito.Mockito.times(4)).store(anyString(), any());
    }

    @Test
    void 画像でないファイルは400で_S3に保存しない() {
      MultipartFile text =
          new MockMultipartFile("images", "a.png", "image/png", "画像ではない".getBytes());

      assertFieldError(
          () -> service.create(ME, "本文", List.of(png(), text)), "images", UploadedImage.TYPE_ERROR);
      verify(imageStorage, never()).store(anyString(), any());
    }

    // ----- エラーの優先順位（文字数 → 枚数 → 形式 → 本文か画像の有無） -----

    @Test
    void 本文が長すぎて画像も多すぎるときは_本文のエラーを返す() {
      assertFieldError(
          () -> service.create(ME, "あ".repeat(281), pngs(5)),
          "content",
          PostService.CONTENT_TOO_LONG);
    }

    @Test
    void 画像が多すぎて形式も違うときは_枚数のエラーを返す() {
      MultipartFile text = new MockMultipartFile("images", "a.png", "image/png", "x".getBytes());

      assertFieldError(
          () -> service.create(ME, null, Collections.nCopies(5, text)),
          "images",
          PostService.TOO_MANY_IMAGES);
    }

    // ----- 分岐網羅（4.6）：保存の途中で失敗 -----

    @Test
    void 画像の2枚目の保存で失敗したら_1枚目を消してエラーにし_投稿は作らない() {
      RuntimeException s3Down = new RuntimeException("S3 に保存できない");
      when(imageStorage.store(anyString(), any(UploadedImage.class)))
          .thenReturn("posts/2026/10/first.png")
          .thenThrow(s3Down);

      assertThatThrownBy(() -> service.create(ME, "本文", pngs(2))).isSameAs(s3Down);
      verify(imageStorage).deleteQuietly("posts/2026/10/first.png");
      verify(postMapper, never()).insert(any());
    }

    @Test
    void 画像は年月_UTC_のフォルダーに保存する() {
      service.create(ME, null, List.of(png()));

      verify(imageStorage).store(eq("posts/2026/10/"), any(UploadedImage.class));
    }
  }

  @Nested
  class 編集_デシジョンテーブル4_3 {

    @Test
    void ケース1_投稿がなければ404() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.empty());

      assertApiError(() -> service.update(ME, 9, "本文"), ErrorCode.POST_NOT_FOUND);
    }

    @Test
    void ケース2_他人の投稿なら403で_更新しない() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.of(post(9, 2, "他人の投稿")));

      assertApiError(() -> service.update(ME, 9, "本文"), ErrorCode.FORBIDDEN);
      verify(postMapper, never()).updateContent(anyLong(), any(), any());
    }

    @Test
    void ケース3_画像のない投稿を空にすると400() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.of(post(9, ME, "前の本文")));
      when(postImageMapper.findByPostIds(List.of(9L))).thenReturn(List.of());

      assertFieldError(
          () -> service.update(ME, 9, "  "),
          "content",
          PostService.CONTENT_REQUIRED_WITHOUT_IMAGES);
    }

    @Test
    void ケース4_画像のある投稿は本文を空にできる() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.of(post(9, ME, "前の本文")));
      when(postImageMapper.findByPostIds(List.of(9L)))
          .thenReturn(List.of(new PostImage(9, "posts/a.png", 1, NOW)));

      service.update(ME, 9, null);

      verify(postMapper).updateContent(9, "", NOW);
    }

    @Test
    void ケース5_本文が変わらなければ更新せず_編集済みにしない() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.of(post(9, ME, "同じ本文")));

      service.update(ME, 9, " 同じ本文 ");

      verify(postMapper, never()).updateContent(anyLong(), any(), any());
    }

    @Test
    void 本文が変われば今の日時で更新する() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.of(post(9, ME, "前の本文")));

      service.update(ME, 9, "新しい本文");

      verify(postMapper).updateContent(9, "新しい本文", NOW);
    }
  }

  @Nested
  class 削除 {

    @Test
    void 自分の投稿は削除し_画像は確定したあとで消す() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.of(post(9, ME, "投稿")));
      when(postImageMapper.findByPostIds(List.of(9L)))
          .thenReturn(List.of(new PostImage(9, "posts/a.png", 1, NOW)));

      service.delete(ME, 9);

      verify(postMapper).delete(9);
      verify(imageStorage).deleteAfterTransaction(List.of("posts/a.png"), List.of());
    }

    @Test
    void 他人の投稿は403_ない投稿は404で_削除しない() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.of(post(9, 2, "他人の投稿")));
      when(postMapper.findById(10, ME)).thenReturn(Optional.empty());

      assertApiError(() -> service.delete(ME, 9), ErrorCode.FORBIDDEN);
      assertApiError(() -> service.delete(ME, 10), ErrorCode.POST_NOT_FOUND);
      verify(postMapper, never()).delete(anyLong());
    }
  }

  @Nested
  class 一覧と件数 {

    @ParameterizedTest(name = "DB から {0} 件取れたら、続きは {1}")
    @CsvSource({"0, false", "20, false", "21, true"})
    void タイムラインは1ページ20件で_21件目があれば続きあり(int rows, boolean hasNext) {
      List<Post> found =
          LongStream.rangeClosed(1, rows).mapToObj(id -> post(id, 2, "投稿" + id)).toList();
      when(postMapper.findAll(ME, null, 21)).thenReturn(found);

      CursorPageResponse<PostResponse> page = service.timeline(ME, null, false);

      assertThat(page.items()).hasSize(Math.min(rows, 20));
      assertThat(page.nextCursor())
          .isEqualTo(hasNext ? CreatedAtCursor.of(NOW.minusSeconds(20), 20).encode() : null);
    }

    @Test
    void 投稿が0件なら_画像を取りに行かない() {
      when(postMapper.findFollowingTimeline(ME, null, 21)).thenReturn(List.of());

      service.timeline(ME, null, true);

      verify(postImageMapper, never()).findByPostIds(any());
    }

    @Test
    void 画像は1ページ分まとめて1回で取り_投稿ごとに分ける() {
      when(postMapper.findFollowingTimeline(ME, null, 21))
          .thenReturn(List.of(post(1, ME, "画像あり"), post(2, 2, "画像なし")));
      when(postImageMapper.findByPostIds(List.of(1L, 2L)))
          .thenReturn(
              List.of(
                  new PostImage(1, "posts/a.png", 1, NOW),
                  new PostImage(1, "posts/b.png", 2, NOW)));
      when(imageStorage.urlOf("posts/a.png")).thenReturn("https://cdn/a.png");
      when(imageStorage.urlOf("posts/b.png")).thenReturn("https://cdn/b.png");

      List<PostResponse> items = service.timeline(ME, null, true).items();

      assertThat(items.get(0).images())
          .containsExactly(
              new PostResponse.PostImageResponse("https://cdn/a.png", 1),
              new PostResponse.PostImageResponse("https://cdn/b.png", 2));
      assertThat(items.get(0).mine()).isTrue();
      assertThat(items.get(1).images()).isEmpty();
      assertThat(items.get(1).mine()).isFalse();
    }

    @Test
    void プロフィールの投稿一覧は_そのユーザーの投稿を続きのカーソル付きで取る() {
      service.userPosts(ME, 2, CreatedAtCursor.of(NOW, 5).encode());

      verify(postMapper).findByUserId(ME, 2, new CreatedAtCursor(NOW, 5), 21);
    }

    @Test
    void 新しい投稿の件数は100件で打ち切るよう頼む() {
      when(postMapper.countNewInFollowingTimeline(ME, 30, 100)).thenReturn(7);
      when(postMapper.countNewInAll(ME, 30, 100)).thenReturn(100);

      assertThat(service.countNewPosts(ME, 30, true).count()).isEqualTo(7);
      assertThat(service.countNewPosts(ME, 30, false).count()).isEqualTo(100);
    }

    @Test
    void ない投稿の詳細は404() {
      when(postMapper.findById(9, ME)).thenReturn(Optional.empty());

      assertApiError(() -> service.get(ME, 9), ErrorCode.POST_NOT_FOUND);
    }
  }
}
