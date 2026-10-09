package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 投稿の作成・詳細・編集・削除と、タイムライン（docs/feature-specs/02_post.md・03_timeline.md）。 */
@Service
public class PostService {

  static final int PAGE_SIZE = 20;

  /** 新しい投稿の件数はここまで数える（それ以上は画面で「99+」と出すので、全部は数えない） */
  static final int NEW_COUNT_LIMIT = 100;

  private final PostMapper postMapper;
  private final Clock clock;

  public PostService(PostMapper postMapper, Clock clock) {
    this.postMapper = postMapper;
    this.clock = clock;
  }

  @Transactional
  public PostResponse create(long me, String content) {
    Post post = new Post(me, content.strip(), OffsetDateTime.now(clock));
    postMapper.insert(post);
    return get(me, post.getId());
  }

  @Transactional(readOnly = true)
  public PostResponse get(long me, long postId) {
    return PostResponse.from(findOrThrow(postId, me), me);
  }

  /** 本文の編集。本文が変わらなければ何もしない（「編集済み」を付けない）。 */
  @Transactional
  public PostResponse update(long me, long postId, String content) {
    Post post = findOwnPostOrThrow(me, postId);
    String newContent = content.strip();
    if (!newContent.equals(post.getContent())) {
      postMapper.updateContent(postId, newContent, OffsetDateTime.now(clock));
    }
    return get(me, postId);
  }

  @Transactional
  public void delete(long me, long postId) {
    findOwnPostOrThrow(me, postId);
    postMapper.delete(postId);
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
    List<PostResponse> items = page.stream().map(post -> PostResponse.from(post, me)).toList();
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
