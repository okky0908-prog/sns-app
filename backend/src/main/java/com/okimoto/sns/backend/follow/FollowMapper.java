package com.okimoto.sns.backend.follow;

import java.time.OffsetDateTime;
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
}
