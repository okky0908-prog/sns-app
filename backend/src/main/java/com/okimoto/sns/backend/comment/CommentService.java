package com.okimoto.sns.backend.comment;

import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** コメントの一覧・投稿・削除（docs/feature-specs/04_comment.md）。 */
@Service
public class CommentService {

  static final int PAGE_SIZE = 20;

  private final CommentMapper commentMapper;
  private final PostMapper postMapper;
  private final Clock clock;

  public CommentService(CommentMapper commentMapper, PostMapper postMapper, Clock clock) {
    this.commentMapper = commentMapper;
    this.postMapper = postMapper;
    this.clock = clock;
  }

  /**
   * コメント一覧（古い順・カーソル方式）。21件取って、21件目があれば「続きあり」とする（タイムラインと同じやり方）。
   *
   * @param cursor 前回のレスポンスの nextCursor。null なら先頭（一番古いもの）から
   */
  @Transactional(readOnly = true)
  public CursorPageResponse<CommentResponse> list(long me, long postId, String cursor) {
    CreatedAtCursor after = CreatedAtCursor.decodeOrNull(cursor);
    requirePost(postId);
    List<Comment> comments = commentMapper.findByPostId(postId, after, PAGE_SIZE + 1);
    boolean hasNext = comments.size() > PAGE_SIZE;
    List<Comment> page = comments.subList(0, Math.min(PAGE_SIZE, comments.size()));
    String nextCursor =
        hasNext
            ? CreatedAtCursor.of(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
            : null;
    List<CommentResponse> items =
        page.stream().map(comment -> CommentResponse.from(comment, me)).toList();
    return new CursorPageResponse<>(items, nextCursor, hasNext);
  }

  @Transactional
  public CreatedCommentResponse create(long me, long postId, String content) {
    requirePost(postId);
    Comment comment = new Comment(postId, me, content.strip(), OffsetDateTime.now(clock));
    try {
      commentMapper.insert(comment);
    } catch (DataIntegrityViolationException e) {
      // 投稿があることを確かめてから INSERT するまでの間に、投稿が削除された（外部キーの違反）
      throw new ApiException(ErrorCode.POST_NOT_FOUND);
    }
    CommentResponse created =
        CommentResponse.from(commentMapper.findById(comment.getId()).orElseThrow(), me);
    return CreatedCommentResponse.of(created, commentMapper.countByPostId(postId));
  }

  /** 削除できるのはコメントした本人だけ（投稿者でも他人のコメントは消せない）。 */
  @Transactional
  public CommentCountResponse delete(long me, long commentId) {
    Comment comment =
        commentMapper
            .findById(commentId)
            .orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND));
    if (comment.getUserId() != me) {
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
    commentMapper.delete(commentId);
    return new CommentCountResponse(commentMapper.countByPostId(comment.getPostId()));
  }

  private void requirePost(long postId) {
    if (!postMapper.existsById(postId)) {
      throw new ApiException(ErrorCode.POST_NOT_FOUND);
    }
  }
}
