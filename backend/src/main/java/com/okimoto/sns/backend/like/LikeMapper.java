package com.okimoto.sns.backend.like;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** likes テーブルへのアクセス。SQL は resources/mapper/LikeMapper.xml に書く。 */
@Mapper
public interface LikeMapper {

  /** いいねする。すでにいいね済みなら何もしない（エラーにしない） */
  void insert(
      @Param("postId") long postId, @Param("userId") long userId, @Param("now") OffsetDateTime now);

  /** いいねを取り消す。いいねしていなければ何もしない */
  void delete(@Param("postId") long postId, @Param("userId") long userId);

  long countByPostId(long postId);

  /** userId がいいね済みか */
  boolean exists(@Param("postId") long postId, @Param("userId") long userId);
}
