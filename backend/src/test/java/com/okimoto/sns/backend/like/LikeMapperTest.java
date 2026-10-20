package com.okimoto.sns.backend.like;

import static com.okimoto.sns.backend.support.TestRows.T0;
import static org.assertj.core.api.Assertions.assertThat;

import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.support.MapperTest;
import com.okimoto.sns.backend.support.TestRows;
import com.okimoto.sns.backend.user.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LikeMapper の SQL（4本）。状態遷移（いいねしていない ⇄ いいね済み）で考える（docs/test-plan.md 4.4）。
 *
 * <p>状態が変わってはいけない遷移（いいね済みでもう一度登録・いいねしていないのに取り消し）も、エラーにならず状態が変わらないことを確かめる。
 */
@MapperTest
class LikeMapperTest {

  @Autowired private LikeMapper likeMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;

  private long alice;
  private long bob;
  private long postId;

  @BeforeEach
  void setUp() {
    TestRows rows = new TestRows(userMapper, postMapper);
    alice = rows.user("alice");
    bob = rows.user("bobby");
    postId = rows.post(alice, "投稿", T0);
  }

  @Test
  void いいねしていない状態では_いいね済みでなく件数は0() {
    assertThat(likeMapper.exists(postId, bob)).isFalse();
    assertThat(likeMapper.countByPostId(postId)).isZero();
  }

  @Test
  void いいねすると_いいね済みになり件数が1増える() {
    likeMapper.insert(postId, bob, T0);

    assertThat(likeMapper.exists(postId, bob)).isTrue();
    assertThat(likeMapper.countByPostId(postId)).isEqualTo(1);
  }

  @Test
  void いいね済みでもう一度いいねしても_エラーにならず件数は増えない() {
    likeMapper.insert(postId, bob, T0);

    likeMapper.insert(postId, bob, T0.plusSeconds(1)); // ON CONFLICT DO NOTHING

    assertThat(likeMapper.countByPostId(postId)).isEqualTo(1);
  }

  @Test
  void 取り消すと_いいね済みでなくなり件数が減る() {
    likeMapper.insert(postId, bob, T0);

    likeMapper.delete(postId, bob);

    assertThat(likeMapper.exists(postId, bob)).isFalse();
    assertThat(likeMapper.countByPostId(postId)).isZero();
  }

  @Test
  void いいねしていないのに取り消しても_エラーにならず件数は変わらない() {
    likeMapper.insert(postId, alice, T0);

    likeMapper.delete(postId, bob);

    assertThat(likeMapper.countByPostId(postId)).isEqualTo(1);
  }

  @Test
  void 件数は全員のいいねを数え_いいね済みかはその人のものだけを見る() {
    long carol = new TestRows(userMapper, postMapper).user("carol");
    likeMapper.insert(postId, alice, T0);
    likeMapper.insert(postId, carol, T0);

    assertThat(likeMapper.countByPostId(postId)).isEqualTo(2);
    assertThat(likeMapper.exists(postId, alice)).isTrue();
    assertThat(likeMapper.exists(postId, bob)).isFalse();
  }

  @Test
  void ほかの投稿のいいねは数えない() {
    long otherPost = new TestRows(userMapper, postMapper).post(alice, "別の投稿", T0);
    likeMapper.insert(otherPost, bob, T0);

    assertThat(likeMapper.countByPostId(postId)).isZero();
    assertThat(likeMapper.exists(postId, bob)).isFalse();
  }
}
