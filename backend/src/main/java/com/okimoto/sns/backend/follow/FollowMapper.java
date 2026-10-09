package com.okimoto.sns.backend.follow;

import com.okimoto.sns.backend.web.CreatedAtCursor;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** follows テーブルへのアクセス。SQL は resources/mapper/FollowMapper.xml に書く。 */
@Mapper
public interface FollowMapper {

  /** フォローする。すでにフォロー中なら何もしない（エラーにしない） */
  void insert(
      @Param("followerId") long followerId,
      @Param("followeeId") long followeeId,
      @Param("now") OffsetDateTime now);

  /** フォローを解除する。フォローしていなければ何もしない */
  void delete(@Param("followerId") long followerId, @Param("followeeId") long followeeId);

  /** フォロワー数 */
  long countFollowers(long userId);

  boolean exists(@Param("followerId") long followerId, @Param("followeeId") long followeeId);

  /** userId がフォローしている人（フォローした日時の新しい順）。me はログイン中のユーザー（各行の「自分がフォロー中か」に使う） */
  List<FollowedUser> findFollowing(
      @Param("userId") long userId,
      @Param("me") long me,
      @Param("cursor") CreatedAtCursor cursor,
      @Param("limit") int limit);

  /** userId をフォローしている人（フォローされた日時の新しい順） */
  List<FollowedUser> findFollowers(
      @Param("userId") long userId,
      @Param("me") long me,
      @Param("cursor") CreatedAtCursor cursor,
      @Param("limit") int limit);
}
