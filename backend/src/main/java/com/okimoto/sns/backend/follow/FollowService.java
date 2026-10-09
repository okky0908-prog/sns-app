package com.okimoto.sns.backend.follow;

import com.okimoto.sns.backend.user.UserService;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * フォロー・フォロー解除（docs/feature-specs/06_follow.md）。
 *
 * <p>何度呼んでも結果が同じになるようにする（フォロー済みでフォロー・フォローしていないのに解除しても、エラーにせず今の状態を返す）。
 */
@Service
public class FollowService {

  private final FollowMapper followMapper;
  private final UserService userService;
  private final Clock clock;

  public FollowService(FollowMapper followMapper, UserService userService, Clock clock) {
    this.followMapper = followMapper;
    this.userService = userService;
    this.clock = clock;
  }

  @Transactional
  public FollowResponse follow(long me, String username) {
    long targetId = userService.findIdOrThrow(username);
    if (targetId == me) {
      throw new ApiException(ErrorCode.CANNOT_FOLLOW_SELF);
    }
    try {
      followMapper.insert(me, targetId, OffsetDateTime.now(clock));
    } catch (DataIntegrityViolationException e) {
      // ユーザーがいることを確かめてから INSERT するまでの間に、相手が削除された（外部キーの違反）
      throw new ApiException(ErrorCode.USER_NOT_FOUND);
    }
    return status(me, targetId);
  }

  @Transactional
  public FollowResponse unfollow(long me, String username) {
    long targetId = userService.findIdOrThrow(username);
    followMapper.delete(me, targetId);
    return status(me, targetId);
  }

  /** 今のフォロー状態と、相手のフォロワー数（その間のほかの人のフォローも反映される） */
  private FollowResponse status(long me, long targetId) {
    return new FollowResponse(
        followMapper.exists(me, targetId), followMapper.countFollowers(targetId));
  }
}
