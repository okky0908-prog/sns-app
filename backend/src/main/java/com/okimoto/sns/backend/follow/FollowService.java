package com.okimoto.sns.backend.follow;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.user.UserListItemResponse;
import com.okimoto.sns.backend.user.UserService;
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

/**
 * フォロー・フォロー解除（docs/feature-specs/06_follow.md）。
 *
 * <p>何度呼んでも結果が同じになるようにする（フォロー済みでフォロー・フォローしていないのに解除しても、エラーにせず今の状態を返す）。
 */
@Service
public class FollowService {

  static final int PAGE_SIZE = 20;

  private final FollowMapper followMapper;
  private final UserService userService;
  private final ImageStorage imageStorage;
  private final Clock clock;

  public FollowService(
      FollowMapper followMapper, UserService userService, ImageStorage imageStorage, Clock clock) {
    this.followMapper = followMapper;
    this.userService = userService;
    this.imageStorage = imageStorage;
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

  /** A-52 username がフォローしている人の一覧（フォローした日時の新しい順・カーソル方式） */
  @Transactional(readOnly = true)
  public CursorPageResponse<UserListItemResponse> following(
      long me, String username, String cursor) {
    CreatedAtCursor after = CreatedAtCursor.decodeOrNull(cursor);
    long userId = userService.findIdOrThrow(username);
    return toPage(me, followMapper.findFollowing(userId, me, after, PAGE_SIZE + 1));
  }

  /** A-53 username をフォローしている人の一覧（フォローされた日時の新しい順・カーソル方式） */
  @Transactional(readOnly = true)
  public CursorPageResponse<UserListItemResponse> followers(
      long me, String username, String cursor) {
    CreatedAtCursor after = CreatedAtCursor.decodeOrNull(cursor);
    long userId = userService.findIdOrThrow(username);
    return toPage(me, followMapper.findFollowers(userId, me, after, PAGE_SIZE + 1));
  }

  /** PAGE_SIZE + 1 件取った結果を、1ページ分と「続きがあるか」に分ける（タイムラインと同じやり方） */
  private CursorPageResponse<UserListItemResponse> toPage(long me, List<FollowedUser> rows) {
    boolean hasNext = rows.size() > PAGE_SIZE;
    List<FollowedUser> page = rows.subList(0, Math.min(PAGE_SIZE, rows.size()));
    String nextCursor =
        hasNext
            ? CreatedAtCursor.of(page.getLast().getFollowedAt(), page.getLast().getFollowId())
                .encode()
            : null;
    List<UserListItemResponse> items =
        page.stream()
            .map(
                row ->
                    UserListItemResponse.of(
                        row.getId(),
                        row.getUsername(),
                        row.getDisplayName(),
                        row.getBio(),
                        imageStorage.urlOf(row.getIconKey()),
                        row.isFollowedByMe(),
                        me))
            .toList();
    return new CursorPageResponse<>(items, nextCursor, hasNext);
  }

  /** 今のフォロー状態と、相手のフォロワー数（その間のほかの人のフォローも反映される） */
  private FollowResponse status(long me, long targetId) {
    return new FollowResponse(
        followMapper.exists(me, targetId), followMapper.countFollowers(targetId));
  }
}
