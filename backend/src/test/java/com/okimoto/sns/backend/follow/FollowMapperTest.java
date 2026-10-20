package com.okimoto.sns.backend.follow;

import static com.okimoto.sns.backend.support.TestRows.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.support.MapperTest;
import com.okimoto.sns.backend.support.TestRows;
import com.okimoto.sns.backend.user.UserMapper;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * FollowMapper の SQL（6本）。
 *
 * <ul>
 *   <li>状態遷移：フォローしていない ⇄ フォロー中（2回フォロー・フォローしていないのに解除しても状態は変わらない）
 *   <li>同値分割：向き（A → B と B → A は別）、一覧の各行の「自分がフォロー中か」は一覧を見ている人の目から見た値
 *   <li>境界値分析：フォローした日時が同じ行は ID の大きい順。カーソルの行そのものは続きに含めない。limit ちょうど
 *   <li>エラー推測：自分自身のフォローは DB の制約で弾く
 * </ul>
 */
@MapperTest
class FollowMapperTest {

  @Autowired private FollowMapper followMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;

  private TestRows rows;
  private long alice;
  private long bob;
  private long carol;

  @BeforeEach
  void setUp() {
    rows = new TestRows(userMapper, postMapper);
    alice = rows.user("alice");
    bob = rows.user("bobby");
    carol = rows.user("carol");
  }

  // ===== フォロー・解除（状態遷移） =====

  @Test
  void フォローすると_その向きだけフォロー中になる() {
    followMapper.insert(alice, bob, T0);

    assertThat(followMapper.exists(alice, bob)).isTrue();
    assertThat(followMapper.exists(bob, alice)).isFalse();
  }

  @Test
  void フォロー中でもう一度フォローしても_エラーにならず1件のまま() {
    followMapper.insert(alice, bob, T0);

    followMapper.insert(alice, bob, T0.plusSeconds(1)); // ON CONFLICT DO NOTHING

    assertThat(followMapper.countFollowers(bob)).isEqualTo(1);
  }

  @Test
  void 解除すると_フォロー中でなくなる() {
    followMapper.insert(alice, bob, T0);

    followMapper.delete(alice, bob);

    assertThat(followMapper.exists(alice, bob)).isFalse();
    assertThat(followMapper.countFollowers(bob)).isZero();
  }

  @Test
  void フォローしていないのに解除しても_エラーにならずほかのフォローは残る() {
    followMapper.insert(carol, bob, T0);

    followMapper.delete(alice, bob);

    assertThat(followMapper.exists(carol, bob)).isTrue();
  }

  @Test
  void 自分自身はフォローできない_DBの制約で弾く() {
    assertThatThrownBy(() -> followMapper.insert(alice, alice, T0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_follows_not_self");
  }

  @Test
  void フォロワー数は_その人をフォローしている人だけを数える() {
    followMapper.insert(alice, bob, T0);
    followMapper.insert(carol, bob, T0);
    followMapper.insert(bob, alice, T0); // bob がフォローしている側は数えない

    assertThat(followMapper.countFollowers(bob)).isEqualTo(2);
    assertThat(followMapper.countFollowers(carol)).isZero();
  }

  // ===== 一覧 =====

  @Test
  void フォロー中一覧は_フォローした日時の新しい順で同じ日時ならIDの大きい順() {
    long dave = rows.user("dave1");
    followMapper.insert(alice, bob, T0);
    followMapper.insert(alice, carol, T0.plusSeconds(10));
    followMapper.insert(alice, dave, T0.plusSeconds(10)); // carol と同じ日時。あとから入れたので ID が大きい

    List<FollowedUser> list = followMapper.findFollowing(alice, alice, null, 20);

    assertThat(list).extracting(FollowedUser::getId).containsExactly(dave, carol, bob);
  }

  @Test
  void フォロワー一覧は_フォローされた日時の新しい順で相手のユーザーが入る() {
    followMapper.insert(bob, alice, T0);
    followMapper.insert(carol, alice, T0.plusSeconds(10));
    followMapper.insert(alice, bob, T0.plusSeconds(20)); // alice がフォローしている側は入らない

    List<FollowedUser> list = followMapper.findFollowers(alice, alice, null, 20);

    assertThat(list).extracting(FollowedUser::getId).containsExactly(carol, bob);
    assertThat(list.getFirst().getUsername()).isEqualTo("carol");
    assertThat(list.getFirst().getDisplayName()).isEqualTo("carolの名前");
    assertThat(list.getFirst().getFollowedAt()).isEqualTo(T0.plusSeconds(10));
  }

  @Test
  void 続きはカーソルの行より古いものだけで_同じ日時でもIDで区切る() {
    long dave = rows.user("dave1");
    followMapper.insert(alice, bob, T0);
    followMapper.insert(alice, carol, T0.plusSeconds(10));
    followMapper.insert(alice, dave, T0.plusSeconds(10));
    List<FollowedUser> first = followMapper.findFollowing(alice, alice, null, 1);
    assertThat(first).extracting(FollowedUser::getId).containsExactly(dave);

    // カーソル = 1ページ目の最後の行（dave）。同じ日時の carol は ID が小さいので続きに入り、dave 自身は入らない
    FollowedUser last = first.getLast();
    List<FollowedUser> next =
        followMapper.findFollowing(
            alice, alice, new CreatedAtCursor(last.getFollowedAt(), last.getFollowId()), 20);

    assertThat(next).extracting(FollowedUser::getId).containsExactly(carol, bob);
  }

  @Test
  void 件数はlimitまで() {
    followMapper.insert(alice, bob, T0);
    followMapper.insert(alice, carol, T0.plusSeconds(1));

    assertThat(followMapper.findFollowing(alice, alice, null, 2)).hasSize(2);
    assertThat(followMapper.findFollowing(alice, alice, null, 1)).hasSize(1);
  }

  @Test
  void 各行の自分がフォロー中かは_一覧を見ている人の目から見た値() {
    // alice のフォロー中一覧（bob・carol）を、carol が見る。carol は bob をフォローしている
    followMapper.insert(alice, bob, T0);
    followMapper.insert(alice, carol, T0.plusSeconds(1));
    followMapper.insert(carol, bob, T0);

    List<FollowedUser> list = followMapper.findFollowing(alice, carol, null, 20);

    assertThat(list)
        .extracting(FollowedUser::getId, FollowedUser::isFollowedByMe)
        .containsExactly(
            tuple(carol, false), // 自分自身の行
            tuple(bob, true));
  }

  @Test
  void フォローしている人がいなければ空() {
    assertThat(followMapper.findFollowing(alice, alice, null, 20)).isEmpty();
    assertThat(followMapper.findFollowers(alice, alice, null, 20)).isEmpty();
  }
}
