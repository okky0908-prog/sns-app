package com.okimoto.sns.backend.post;

import static com.okimoto.sns.backend.support.TestRows.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.okimoto.sns.backend.support.MapperTest;
import com.okimoto.sns.backend.support.TestRows;
import com.okimoto.sns.backend.user.UserMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PostImageMapper の SQL（2本）。
 *
 * <ul>
 *   <li>同値分割：1つの投稿・複数の投稿をまとめて取る、画像のない投稿
 *   <li>境界値分析：表示順は 1〜4（0・5 は DB の制約で弾く）
 *   <li>エラー推測：同じ投稿で同じ表示順は弾く
 * </ul>
 */
@MapperTest
class PostImageMapperTest {

  @Autowired private PostImageMapper postImageMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;

  private TestRows rows;
  private long alice;

  @BeforeEach
  void setUp() {
    rows = new TestRows(userMapper, postMapper);
    alice = rows.user("alice");
  }

  @Test
  void まとめて登録し_投稿ID_表示順の順で取れる() {
    long post1 = rows.post(alice, "1", T0);
    long post2 = rows.post(alice, "2", T0);
    // わざと表示順・投稿の順をばらばらに入れる
    postImageMapper.insertAll(
        List.of(
            new PostImage(post2, "posts/b2.png", 2, T0),
            new PostImage(post2, "posts/b1.png", 1, T0)));
    postImageMapper.insertAll(
        List.of(
            new PostImage(post1, "posts/a1.png", 1, T0),
            new PostImage(post1, "posts/a2.png", 2, T0),
            new PostImage(post1, "posts/a3.png", 3, T0),
            new PostImage(post1, "posts/a4.png", 4, T0)));

    List<PostImage> images = postImageMapper.findByPostIds(List.of(post2, post1));

    assertThat(images)
        .extracting(PostImage::getPostId, PostImage::getSortOrder, PostImage::getImageKey)
        .containsExactly(
            tuple(post1, 1, "posts/a1.png"),
            tuple(post1, 2, "posts/a2.png"),
            tuple(post1, 3, "posts/a3.png"),
            tuple(post1, 4, "posts/a4.png"),
            tuple(post2, 1, "posts/b1.png"),
            tuple(post2, 2, "posts/b2.png"));
  }

  @Test
  void 画像のない投稿と_頼んでいない投稿の画像は入らない() {
    long withImage = rows.post(alice, "画像あり", T0);
    long withoutImage = rows.post(alice, "画像なし", T0);
    long notAsked = rows.post(alice, "頼んでいない", T0);
    postImageMapper.insertAll(List.of(new PostImage(withImage, "posts/x.png", 1, T0)));
    postImageMapper.insertAll(List.of(new PostImage(notAsked, "posts/y.png", 1, T0)));

    assertThat(postImageMapper.findByPostIds(List.of(withImage, withoutImage)))
        .extracting(PostImage::getImageKey)
        .containsExactly("posts/x.png");
    assertThat(postImageMapper.findByPostIds(List.of(withoutImage))).isEmpty();
  }

  @ParameterizedTest(name = "表示順 {0} は DB の制約で弾く")
  @ValueSource(ints = {0, 5})
  void 表示順は1から4まで(int sortOrder) {
    long postId = rows.post(alice, "投稿", T0);

    assertThatThrownBy(
            () -> postImageMapper.insertAll(List.of(new PostImage(postId, "k", sortOrder, T0))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_post_images_sort_order");
  }

  @Test
  void 同じ投稿で同じ表示順は弾く() {
    long postId = rows.post(alice, "投稿", T0);

    assertThatThrownBy(
            () ->
                postImageMapper.insertAll(
                    List.of(new PostImage(postId, "a", 1, T0), new PostImage(postId, "b", 1, T0))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_post_images_post_sort");
  }
}
