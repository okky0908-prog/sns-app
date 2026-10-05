package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.PageResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
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
    return PostResponse.from(findOrThrow(postId), me);
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
   * タイムライン。21件取って、21件目があれば「次のページあり」とする（件数を数える SQL を別に発行しないため）。
   *
   * @param following true ならフォロー中タイムライン、false なら全体タイムライン
   */
  @Transactional(readOnly = true)
  public PageResponse<PostResponse> timeline(long me, int page, boolean following) {
    int offset = page * PAGE_SIZE;
    List<Post> posts =
        following
            ? postMapper.findFollowingTimeline(me, PAGE_SIZE + 1, offset)
            : postMapper.findAll(PAGE_SIZE + 1, offset);
    boolean hasNext = posts.size() > PAGE_SIZE;
    List<PostResponse> items =
        posts.stream().limit(PAGE_SIZE).map(post -> PostResponse.from(post, me)).toList();
    return new PageResponse<>(items, page, hasNext);
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
