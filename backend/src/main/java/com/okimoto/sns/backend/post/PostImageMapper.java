package com.okimoto.sns.backend.post;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** post_images テーブルへのアクセス。SQL は resources/mapper/PostImageMapper.xml に書く。 */
@Mapper
public interface PostImageMapper {

  /** 1つの投稿の画像をまとめて登録する（INSERT 1回） */
  void insertAll(@Param("images") List<PostImage> images);

  /**
   * 表示する投稿の画像をまとめて取る（SQL 1回。投稿ごとに取らない＝N+1 にしない）。投稿 ID・表示順の順。
   *
   * @param postIds 空にしないこと（空のときは呼ばない）
   */
  List<PostImage> findByPostIds(@Param("postIds") Collection<Long> postIds);
}
