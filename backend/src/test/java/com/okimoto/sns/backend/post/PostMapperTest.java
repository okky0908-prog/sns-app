package com.okimoto.sns.backend.post;

import static com.okimoto.sns.backend.support.TestRows.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okimoto.sns.backend.comment.Comment;
import com.okimoto.sns.backend.comment.CommentMapper;
import com.okimoto.sns.backend.follow.FollowMapper;
import com.okimoto.sns.backend.like.LikeMapper;
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
 * PostMapper の SQL（10本）。
 *
 * <ul>
 *   <li>同値分割：タイムラインに出る投稿（自分・フォロー中の人）と出ない投稿（フォローしていない人）。いいね済みかは見ている人の目から見た値
 *   <li>境界値分析：新しい順で同じ日時なら ID の大きい順、カーソルの行そのものは続きに含めない、limit ちょうど。新しい投稿の件数は since と同じ ID を数えず、limit
 *       で打ち切る。本文 280・281文字
 *   <li>エラー推測：投稿を消すと、画像・いいね・コメントも一緒に消える（連鎖削除）
 * </ul>
 */
@MapperTest
class PostMapperTest {

  @Autowired private PostMapper postMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private FollowMapper followMapper;
  @Autowired private LikeMapper likeMapper;
  @Autowired private CommentMapper commentMapper;
  @Autowired private PostImageMapper postImageMapper;

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

  private List<Long> ids(List<Post> posts) {
    return posts.stream().map(Post::getId).toList();
  }

  // ===== 登録・1件の取得 =====

  @Test
  void 登録すると_IDが振られ_投稿者の情報と一緒に取れる() {
    long id = rows.post(alice, "今日はSpring Bootの勉強をしました。", T0);

    Post post = postMapper.findById(id, bob).orElseThrow();

    assertThat(post.getUserId()).isEqualTo(alice);
    assertThat(post.getContent()).isEqualTo("今日はSpring Bootの勉強をしました。");
    assertThat(post.getCreatedAt()).isEqualTo(T0);
    assertThat(post.getUpdatedAt()).isEqualTo(T0);
    assertThat(post.getEditedAt()).isNull();
    assertThat(post.getAuthorUsername()).isEqualTo("alice");
    assertThat(post.getAuthorDisplayName()).isEqualTo("aliceの名前");
    assertThat(post.getLikeCount()).isZero();
    assertThat(post.isLikedByMe()).isFalse();
    assertThat(post.getCommentCount()).isZero();
  }

  @Test
  void いいね数とコメント数は全員の分を数え_いいね済みかは見ている人の分だけを見る() {
    long id = rows.post(alice, "投稿", T0);
    likeMapper.insert(id, bob, T0);
    likeMapper.insert(id, carol, T0);
    commentMapper.insert(new Comment(id, bob, "コメント", T0));

    Post seenByBob = postMapper.findById(id, bob).orElseThrow();
    Post seenByAlice = postMapper.findById(id, alice).orElseThrow();

    assertThat(seenByBob.getLikeCount()).isEqualTo(2);
    assertThat(seenByBob.getCommentCount()).isEqualTo(1);
    assertThat(seenByBob.isLikedByMe()).isTrue();
    assertThat(seenByAlice.isLikedByMe()).isFalse();
  }

  @Test
  void ない投稿は見つからない() {
    long id = rows.post(alice, "投稿", T0);

    assertThat(postMapper.findById(Long.MAX_VALUE, alice)).isEmpty();
    assertThat(postMapper.existsById(id)).isTrue();
    assertThat(postMapper.existsById(Long.MAX_VALUE)).isFalse();
  }

  @Test
  void 本文は絵文字280個まで保存でき_281文字はDBが弾く() {
    long id = rows.post(alice, "🎉".repeat(280), T0);

    assertThat(postMapper.findById(id, alice).orElseThrow().getContent())
        .isEqualTo("🎉".repeat(280));
    assertThatThrownBy(() -> rows.post(alice, "あ".repeat(281), T0))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ===== タイムライン =====

  @Test
  void フォロー中タイムラインは_自分とフォロー中の人の投稿だけ() {
    followMapper.insert(alice, bob, T0);
    long mine = rows.post(alice, "自分", T0);
    long followee = rows.post(bob, "フォロー中の人", T0.plusSeconds(1));
    rows.post(carol, "フォローしていない人", T0.plusSeconds(2));

    assertThat(ids(postMapper.findFollowingTimeline(alice, null, 20)))
        .containsExactly(followee, mine);
  }

  @Test
  void タイムラインは新しい順で_同じ日時ならIDの大きい順() {
    long oldest = rows.post(alice, "1", T0);
    long sameTime1 = rows.post(bob, "2", T0.plusSeconds(10));
    long sameTime2 = rows.post(carol, "3", T0.plusSeconds(10));

    assertThat(ids(postMapper.findAll(alice, null, 20)))
        .containsExactly(sameTime2, sameTime1, oldest);
  }

  @Test
  void 続きはカーソルの行より古いものだけで_同じ日時でもIDで区切る() {
    followMapper.insert(alice, bob, T0);
    long oldest = rows.post(alice, "1", T0);
    long sameTime1 = rows.post(bob, "2", T0.plusSeconds(10));
    long sameTime2 = rows.post(alice, "3", T0.plusSeconds(10));
    CreatedAtCursor afterNewest = new CreatedAtCursor(T0.plusSeconds(10), sameTime2);

    assertThat(ids(postMapper.findAll(alice, afterNewest, 20))).containsExactly(sameTime1, oldest);
    assertThat(ids(postMapper.findFollowingTimeline(alice, afterNewest, 20)))
        .containsExactly(sameTime1, oldest);
    assertThat(ids(postMapper.findByUserId(alice, alice, afterNewest, 20))).containsExactly(oldest);
  }

  @Test
  void 件数はlimitまで() {
    rows.post(alice, "1", T0);
    rows.post(alice, "2", T0.plusSeconds(1));
    rows.post(alice, "3", T0.plusSeconds(2));

    assertThat(postMapper.findAll(alice, null, 2)).hasSize(2);
    assertThat(postMapper.findFollowingTimeline(alice, null, 2)).hasSize(2);
    assertThat(postMapper.findByUserId(alice, alice, null, 2)).hasSize(2);
  }

  @Test
  void そのユーザーの投稿一覧は_その人の投稿だけ() {
    long bobsPost = rows.post(bob, "bob", T0);
    rows.post(alice, "alice", T0.plusSeconds(1));

    assertThat(ids(postMapper.findByUserId(alice, bob, null, 20))).containsExactly(bobsPost);
  }

  // ===== 新しい投稿の件数 =====

  @Test
  void フォロー中の新しい投稿は_sinceより大きいIDのフォロー中の人の投稿だけを数える() {
    followMapper.insert(alice, bob, T0);
    long since = rows.post(bob, "画面がすでに持っている投稿", T0);
    rows.post(bob, "新しい1", T0.plusSeconds(1));
    rows.post(bob, "新しい2", T0.plusSeconds(2));
    rows.post(alice, "自分の投稿は数えない", T0.plusSeconds(3));
    rows.post(carol, "フォローしていない人は数えない", T0.plusSeconds(4));

    assertThat(postMapper.countNewInFollowingTimeline(alice, since, 100)).isEqualTo(2);
  }

  @Test
  void 全体の新しい投稿は_sinceより大きいIDの他人の投稿を数える() {
    long since = rows.post(bob, "画面がすでに持っている投稿", T0);
    rows.post(bob, "新しい1", T0.plusSeconds(1));
    rows.post(carol, "新しい2", T0.plusSeconds(2));
    rows.post(alice, "自分の投稿は数えない", T0.plusSeconds(3));

    assertThat(postMapper.countNewInAll(alice, since, 100)).isEqualTo(2);
    // since と同じ ID の投稿は「新しい」に入らない（境界）
    assertThat(postMapper.countNewInAll(alice, since - 1, 100)).isEqualTo(3);
  }

  @Test
  void 新しい投稿の件数はlimitで打ち切る() {
    followMapper.insert(alice, bob, T0);
    for (int i = 0; i < 4; i++) {
      rows.post(bob, "新しい" + i, T0.plusSeconds(i));
    }

    assertThat(postMapper.countNewInAll(alice, 0, 3)).isEqualTo(3);
    assertThat(postMapper.countNewInFollowingTimeline(alice, 0, 3)).isEqualTo(3);
    assertThat(postMapper.countNewInAll(alice, 0, 4)).isEqualTo(4);
  }

  // ===== 編集・削除 =====

  @Test
  void 編集すると本文と編集日時が変わる() {
    long id = rows.post(alice, "前の本文", T0);

    int updated = postMapper.updateContent(id, "新しい本文", T0.plusMinutes(5));

    Post post = postMapper.findById(id, alice).orElseThrow();
    assertThat(updated).isEqualTo(1);
    assertThat(post.getContent()).isEqualTo("新しい本文");
    assertThat(post.getEditedAt()).isEqualTo(T0.plusMinutes(5));
    assertThat(post.getUpdatedAt()).isEqualTo(T0.plusMinutes(5));
    assertThat(post.getCreatedAt()).isEqualTo(T0);
  }

  @Test
  void ない投稿の編集と削除は0件() {
    assertThat(postMapper.updateContent(Long.MAX_VALUE, "本文", T0)).isZero();
    assertThat(postMapper.delete(Long.MAX_VALUE)).isZero();
  }

  @Test
  void 削除すると_画像_いいね_コメントも一緒に消え_ほかの投稿は残る() {
    long id = rows.post(alice, "消す投稿", T0);
    long other = rows.post(alice, "残す投稿", T0);
    postImageMapper.insertAll(List.of(new PostImage(id, "posts/a.png", 1, T0)));
    likeMapper.insert(id, bob, T0);
    commentMapper.insert(new Comment(id, bob, "コメント", T0));
    likeMapper.insert(other, bob, T0);

    int deleted = postMapper.delete(id);

    assertThat(deleted).isEqualTo(1);
    assertThat(postMapper.existsById(id)).isFalse();
    assertThat(postImageMapper.findByPostIds(List.of(id))).isEmpty();
    assertThat(likeMapper.countByPostId(id)).isZero();
    assertThat(commentMapper.countByPostId(id)).isZero();
    assertThat(likeMapper.countByPostId(other)).isEqualTo(1);
  }
}
