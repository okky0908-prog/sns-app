package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.storage.UploadedImage;
import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 投稿の作成・詳細・編集・削除と、タイムライン（docs/feature-specs/02_post.md・03_timeline.md）。 */
@Service
public class PostService {

  static final int PAGE_SIZE = 20;

  /** 新しい投稿の件数はここまで数える（それ以上は画面で「99+」と出すので、全部は数えない） */
  static final int NEW_COUNT_LIMIT = 100;

  static final int MAX_CONTENT = 280;
  static final int MAX_IMAGES = 4;

  static final String CONTENT_TOO_LONG = "本文は280文字以内で入力してください";
  static final String CONTENT_OR_IMAGE_REQUIRED = "本文を入力するか、画像を選択してください";
  static final String CONTENT_REQUIRED_WITHOUT_IMAGES = "画像のない投稿は本文を空にできません";
  static final String TOO_MANY_IMAGES = "画像は4枚まで添付できます";

  private final PostMapper postMapper;
  private final PostImageMapper postImageMapper;
  private final ImageStorage imageStorage;
  private final Clock clock;

  public PostService(
      PostMapper postMapper,
      PostImageMapper postImageMapper,
      ImageStorage imageStorage,
      Clock clock) {
    this.postMapper = postMapper;
    this.postImageMapper = postImageMapper;
    this.imageStorage = imageStorage;
    this.clock = clock;
  }

  /**
   * 投稿の作成（A-11）。本文と画像のどちらか一方は必要。
   *
   * <p>入力をすべて確かめてから画像を S3 に保存し、そのあと DB に保存する（「DB には行があるのに画像がない」状態を作らないため）。 DB
   * の保存が取り消されたら、保存した画像を削除する。
   *
   * @param content 本文（なくてもよい）。ブラウザがフォームで送る改行（\r\n）は \n にそろえる
   * @param images 画像（4枚まで。選んだ順に表示する）
   */
  @Transactional
  public PostResponse create(long me, String content, List<MultipartFile> images) {
    String text = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n').strip();
    List<MultipartFile> files =
        images == null ? List.of() : images.stream().filter(file -> !file.isEmpty()).toList();
    if (text.codePointCount(0, text.length()) > MAX_CONTENT) {
      throw fieldError("content", CONTENT_TOO_LONG);
    }
    if (files.size() > MAX_IMAGES) {
      throw fieldError("images", TOO_MANY_IMAGES);
    }
    List<UploadedImage> uploads =
        files.stream().map(file -> UploadedImage.from(file, "images")).toList();
    if (text.isEmpty() && uploads.isEmpty()) {
      throw fieldError("content", CONTENT_OR_IMAGE_REQUIRED);
    }

    OffsetDateTime now = OffsetDateTime.now(clock);
    List<String> keys = storeAll(uploads, now);
    imageStorage.deleteAfterTransaction(List.of(), keys);

    Post post = new Post(me, text, now);
    postMapper.insert(post);
    if (!keys.isEmpty()) {
      List<PostImage> rows = new ArrayList<>();
      for (int i = 0; i < keys.size(); i++) {
        rows.add(new PostImage(post.getId(), keys.get(i), i + 1, now));
      }
      postImageMapper.insertAll(rows);
    }
    return get(me, post.getId());
  }

  /** 画像を S3 の posts/{年}/{月}/ に保存し、キーを返す（年月は UTC）。途中で失敗したら、それまでに保存した画像を消してからエラーにする */
  private List<String> storeAll(List<UploadedImage> uploads, OffsetDateTime now) {
    String prefix = String.format("posts/%04d/%02d/", now.getYear(), now.getMonthValue());
    List<String> keys = new ArrayList<>();
    try {
      for (UploadedImage upload : uploads) {
        keys.add(imageStorage.store(prefix, upload));
      }
    } catch (RuntimeException e) {
      keys.forEach(imageStorage::deleteQuietly);
      throw e;
    }
    return keys;
  }

  @Transactional(readOnly = true)
  public PostResponse get(long me, long postId) {
    return toResponses(List.of(findOrThrow(postId, me)), me).getFirst();
  }

  /** 本文の編集。本文が変わらなければ何もしない（「編集済み」を付けない）。画像のない投稿は本文を空にできない。 */
  @Transactional
  public PostResponse update(long me, long postId, String content) {
    Post post = findOwnPostOrThrow(me, postId);
    String newContent = content == null ? "" : content.strip();
    if (newContent.isEmpty() && imageKeysOf(postId).isEmpty()) {
      throw fieldError("content", CONTENT_REQUIRED_WITHOUT_IMAGES);
    }
    if (!newContent.equals(post.getContent())) {
      postMapper.updateContent(postId, newContent, OffsetDateTime.now(clock));
    }
    return get(me, postId);
  }

  /** 投稿の削除。画像の行は CASCADE で消え、S3 の画像は DB のコミット後に消す（失敗してもログだけ）。 */
  @Transactional
  public void delete(long me, long postId) {
    findOwnPostOrThrow(me, postId);
    List<String> keys = imageKeysOf(postId);
    postMapper.delete(postId);
    imageStorage.deleteAfterTransaction(keys, List.of());
  }

  private List<String> imageKeysOf(long postId) {
    return postImageMapper.findByPostIds(List.of(postId)).stream()
        .map(PostImage::getImageKey)
        .toList();
  }

  /**
   * タイムライン（カーソル方式）。21件取って、21件目があれば「続きあり」とする（件数を数える SQL を別に発行しないため）。
   *
   * @param cursor 前回のレスポンスの nextCursor。null なら先頭（最新）から
   * @param following true ならフォロー中タイムライン、false なら全体タイムライン
   */
  @Transactional(readOnly = true)
  public CursorPageResponse<PostResponse> timeline(long me, String cursor, boolean following) {
    CreatedAtCursor after = CreatedAtCursor.decodeOrNull(cursor);
    return toPage(
        me,
        following
            ? postMapper.findFollowingTimeline(me, after, PAGE_SIZE + 1)
            : postMapper.findAll(me, after, PAGE_SIZE + 1));
  }

  /** そのユーザーの投稿一覧（プロフィール。新しい順・カーソル方式。中身はタイムラインと同じ） */
  @Transactional(readOnly = true)
  public CursorPageResponse<PostResponse> userPosts(long me, long userId, String cursor) {
    CreatedAtCursor after = CreatedAtCursor.decodeOrNull(cursor);
    return toPage(me, postMapper.findByUserId(me, userId, after, PAGE_SIZE + 1));
  }

  /** PAGE_SIZE + 1 件取った結果を、1ページ分と「続きがあるか」に分ける */
  private CursorPageResponse<PostResponse> toPage(long me, List<Post> posts) {
    boolean hasNext = posts.size() > PAGE_SIZE;
    List<Post> page = posts.subList(0, Math.min(PAGE_SIZE, posts.size()));
    String nextCursor =
        hasNext
            ? CreatedAtCursor.of(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
            : null;
    List<PostResponse> items = toResponses(page, me);
    return new CursorPageResponse<>(items, nextCursor, hasNext);
  }

  /**
   * 画面が最後に取った一番新しい投稿（since）より後に増えた投稿の件数。「↑ N件の新しい投稿」に使う。
   *
   * <p>自分の投稿は投稿した時点で画面に出ているので数えない。NEW_COUNT_LIMIT 件で数えるのをやめる。
   */
  @Transactional(readOnly = true)
  public NewPostCountResponse countNewPosts(long me, long since, boolean following) {
    int count =
        following
            ? postMapper.countNewInFollowingTimeline(me, since, NEW_COUNT_LIMIT)
            : postMapper.countNewInAll(me, since, NEW_COUNT_LIMIT);
    return new NewPostCountResponse(count);
  }

  /** レスポンスにする。画像は表示する投稿の ID でまとめて1回で取る（投稿ごとに取らない＝N+1 にしない） */
  private List<PostResponse> toResponses(List<Post> posts, long me) {
    Map<Long, List<PostResponse.PostImageResponse>> imagesByPost = new HashMap<>();
    if (!posts.isEmpty()) {
      List<Long> ids = posts.stream().map(Post::getId).toList();
      for (PostImage image : postImageMapper.findByPostIds(ids)) {
        imagesByPost
            .computeIfAbsent(image.getPostId(), id -> new ArrayList<>())
            .add(
                new PostResponse.PostImageResponse(
                    imageStorage.urlOf(image.getImageKey()), image.getSortOrder()));
      }
    }
    return posts.stream()
        .map(
            post ->
                PostResponse.from(
                    post,
                    me,
                    imageStorage.urlOf(post.getAuthorIconKey()),
                    imagesByPost.getOrDefault(post.getId(), List.of())))
        .toList();
  }

  private static ApiException fieldError(String field, String message) {
    return ApiException.badRequest(List.of(new ApiError.FieldError(field, message)));
  }

  private Post findOrThrow(long postId, long me) {
    return postMapper
        .findById(postId, me)
        .orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
  }

  /** 投稿が存在し、自分の投稿であることを確かめる（他人の投稿の編集・削除は 403） */
  private Post findOwnPostOrThrow(long me, long postId) {
    Post post = findOrThrow(postId, me);
    if (post.getUserId() != me) {
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
    return post;
  }
}
