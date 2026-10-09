package com.okimoto.sns.backend.like;

import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * いいね・いいね取り消し（docs/feature-specs/05_like.md）。
 *
 * <p>何度呼んでも結果が同じになるようにする（いいね済みでいいね・いいねなしで取り消しても、エラーにせず今の状態を返す）。
 * ボタンの連打や通信の再送があっても、数がずれたりエラーになったりしないようにするため。
 */
@Service
public class LikeService {

  private final LikeMapper likeMapper;
  private final PostMapper postMapper;
  private final Clock clock;

  public LikeService(LikeMapper likeMapper, PostMapper postMapper, Clock clock) {
    this.likeMapper = likeMapper;
    this.postMapper = postMapper;
    this.clock = clock;
  }

  @Transactional
  public LikeResponse like(long me, long postId) {
    requirePost(postId);
    try {
      likeMapper.insert(postId, me, OffsetDateTime.now(clock));
    } catch (DataIntegrityViolationException e) {
      // 投稿があることを確かめてから INSERT するまでの間に、投稿が削除された（外部キーの違反）
      throw new ApiException(ErrorCode.POST_NOT_FOUND);
    }
    return status(me, postId);
  }

  @Transactional
  public LikeResponse unlike(long me, long postId) {
    requirePost(postId);
    likeMapper.delete(postId, me);
    return status(me, postId);
  }

  /** 今のいいね数と、me がいいね済みか（その間のほかの人のいいねも反映される） */
  private LikeResponse status(long me, long postId) {
    return new LikeResponse(likeMapper.countByPostId(postId), likeMapper.exists(postId, me));
  }

  private void requirePost(long postId) {
    if (!postMapper.existsById(postId)) {
      throw new ApiException(ErrorCode.POST_NOT_FOUND);
    }
  }
}
