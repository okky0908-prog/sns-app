package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CursorPageResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 投稿の作成・詳細・編集・削除と、タイムライン（docs/feature-specs/02_post.md・03_timeline.md）。 */
@Service
public class PostService {

  static final int PAGE_SIZE = 20;
  static final String NOT_FOUND = "この投稿は見つかりません";
  static final String FORBIDDEN = "この操作は実行できません";

  private final PostMapper postMapper;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public PostService(PostMapper postMapper, ApplicationEventPublisher events, Clock clock) {
    this.postMapper = postMapper;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public PostResponse create(long me, String content) {
    Post post = new Post(me, content.strip(), OffsetDateTime.now(clock));
    postMapper.insert(post);
    events.publishEvent(new PostEvent.Created(post.getId())); // 画面への通知はコミット後
    return get(me, post.getId());
  }

  @Transactional(readOnly = true)
  public PostResponse get(long me, long postId) {
    return PostResponse.from(findOrThrow(postId), me);
  }

  /** 本文の編集。本文が変わらなければ何もしない（「編集済み」を付けない）。 */
  @Transactional
  public PostResponse update(long me, long postId, String content) {
    Post post = findOwnPostOrThrow(me, postId);
    String newContent = content.strip();
    if (!newContent.equals(post.getContent())) {
      postMapper.updateContent(postId, newContent, OffsetDateTime.now(clock));
      events.publishEvent(new PostEvent.Updated(postId));
    }
    return get(me, postId);
  }

  @Transactional
  public void delete(long me, long postId) {
    findOwnPostOrThrow(me, postId);
    postMapper.delete(postId);
    events.publishEvent(new PostEvent.Deleted(postId));
  }

  /**
   * タイムライン（カーソル方式）。21件取って、21件目があれば「続きあり」とする（件数を数える SQL を別に発行しないため）。
   *
   * @param cursor 前回のレスポンスの nextCursor。null なら先頭（最新）から
   * @param following true ならフォロー中タイムライン、false なら全体タイムライン
   */
  @Transactional(readOnly = true)
  public CursorPageResponse<PostResponse> timeline(long me, String cursor, boolean following) {
    TimelineCursor after =
        cursor == null || cursor.isBlank() ? null : TimelineCursor.decode(cursor);
    List<Post> posts =
        following
            ? postMapper.findFollowingTimeline(me, after, PAGE_SIZE + 1)
            : postMapper.findAll(after, PAGE_SIZE + 1);
    boolean hasNext = posts.size() > PAGE_SIZE;
    List<Post> page = posts.subList(0, Math.min(PAGE_SIZE, posts.size()));
    String nextCursor = hasNext ? TimelineCursor.of(page.getLast()).encode() : null;
    List<PostResponse> items = page.stream().map(post -> PostResponse.from(post, me)).toList();
    return new CursorPageResponse<>(items, nextCursor, hasNext);
  }

  private Post findOrThrow(long postId) {
    return postMapper
        .findById(postId)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
  }

  /** 投稿が存在し、自分の投稿であることを確かめる（他人の投稿の編集・削除は 403） */
  private Post findOwnPostOrThrow(long me, long postId) {
    Post post = findOrThrow(postId);
    if (post.getUserId() != me) {
      throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
    }
    return post;
  }
}
