package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.web.CreatedAtCursor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** posts テーブルへのアクセス。SQL は resources/mapper/PostMapper.xml に書く。 */
@Mapper
public interface PostMapper {

  /** 投稿者の情報・いいね数・me がいいね済みかも一緒に取る。 */
  Optional<Post> findById(@Param("id") long id, @Param("me") long me);

  /** フォロー中タイムライン（自分＋フォロー中の人の投稿）。新しい順。cursor が null なら先頭から。 */
  List<Post> findFollowingTimeline(
      @Param("me") long me, @Param("cursor") CreatedAtCursor cursor, @Param("limit") int limit);

  /** 全体タイムライン（全ユーザーの投稿）。新しい順。cursor が null なら先頭から。 */
  List<Post> findAll(
      @Param("me") long me, @Param("cursor") CreatedAtCursor cursor, @Param("limit") int limit);

  /** フォロー中タイムラインで、since より新しい他人の投稿の件数（limit 件まで）。 */
  int countNewInFollowingTimeline(
      @Param("me") long me, @Param("since") long since, @Param("limit") int limit);

  /** 全体タイムラインで、since より新しい他人の投稿の件数（limit 件まで）。 */
  int countNewInAll(@Param("me") long me, @Param("since") long since, @Param("limit") int limit);

  boolean existsById(long id);

  /** 登録後、採番された ID を post に入れる。 */
  void insert(Post post);

  int updateContent(
      @Param("id") long id, @Param("content") String content, @Param("now") OffsetDateTime now);

  int delete(long id);
}
