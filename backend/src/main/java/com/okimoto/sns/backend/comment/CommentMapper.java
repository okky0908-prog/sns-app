package com.okimoto.sns.backend.comment;

import com.okimoto.sns.backend.web.CreatedAtCursor;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** comments テーブルへのアクセス。SQL は resources/mapper/CommentMapper.xml に書く。 */
@Mapper
public interface CommentMapper {

  /** 書いた人の情報も一緒に取る。 */
  Optional<Comment> findById(long id);

  /** その投稿のコメント（書いた人の情報付き）。古い順。cursor が null なら先頭から、あればそれより新しいもの。 */
  List<Comment> findByPostId(
      @Param("postId") long postId,
      @Param("cursor") CreatedAtCursor cursor,
      @Param("limit") int limit);

  long countByPostId(long postId);

  /** 登録後、採番された ID を comment に入れる。 */
  void insert(Comment comment);

  void delete(long id);
}
