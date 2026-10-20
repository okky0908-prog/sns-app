package com.okimoto.sns.backend.comment;

import static com.okimoto.sns.backend.support.TestRows.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.support.MapperTest;
import com.okimoto.sns.backend.support.TestRows;
import com.okimoto.sns.backend.user.UserMapper;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * CommentMapper の SQL（5本）。
 *
 * <ul>
 *   <li>同値分割：見つかる・見つからない、その投稿のコメント・ほかの投稿のコメント
 *   <li>境界値分析：古い順で同じ日時なら ID の小さい順。カーソルの行そのものは続きに含めない。limit ちょうど。本文 280・281文字（DB の上限がアプリの数え方と同じか）
 * </ul>
 */
@MapperTest
class CommentMapperTest {

  @Autowired private CommentMapper commentMapper;
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

  private long comment(long userId, String content, OffsetDateTime createdAt) {
    Comment comment = new Comment(postId, userId, content, createdAt);
    commentMapper.insert(comment);
    return comment.getId();
  }

  @Test
  void 登録すると_IDが振られ_書いた人の情報と一緒に取れる() {
    long id = comment(bob, "わかりやすいです！", T0);

    Comment found = commentMapper.findById(id).orElseThrow();

    assertThat(found.getPostId()).isEqualTo(postId);
    assertThat(found.getUserId()).isEqualTo(bob);
    assertThat(found.getContent()).isEqualTo("わかりやすいです！");
    assertThat(found.getCreatedAt()).isEqualTo(T0);
    assertThat(found.getAuthorUsername()).isEqualTo("bobby");
    assertThat(found.getAuthorDisplayName()).isEqualTo("bobbyの名前");
    assertThat(found.getAuthorIconKey()).isNull();
  }

  @Test
  void ないコメントは見つからない() {
    assertThat(commentMapper.findById(Long.MAX_VALUE)).isEmpty();
  }

  @Test
  void 一覧は古い順で_同じ日時ならIDの小さい順() {
    long second = comment(bob, "2番目", T0.plusSeconds(10));
    long first = comment(alice, "1番目", T0);
    long third = comment(alice, "3番目", T0.plusSeconds(10)); // second と同じ日時。あとから入れたので ID が大きい

    List<Comment> list = commentMapper.findByPostId(postId, null, 20);

    assertThat(list).extracting(Comment::getId).containsExactly(first, second, third);
  }

  @Test
  void 続きはカーソルの行より新しいものだけで_同じ日時でもIDで区切る() {
    long first = comment(alice, "1番目", T0);
    long second = comment(bob, "2番目", T0.plusSeconds(10));
    long third = comment(alice, "3番目", T0.plusSeconds(10));

    List<Comment> next =
        commentMapper.findByPostId(postId, new CreatedAtCursor(T0.plusSeconds(10), second), 20);

    assertThat(next).extracting(Comment::getId).containsExactly(third);
    assertThat(commentMapper.findByPostId(postId, new CreatedAtCursor(T0, first), 20))
        .extracting(Comment::getId)
        .containsExactly(second, third);
  }

  @Test
  void 件数はlimitまでで_ほかの投稿のコメントは入らない() {
    long otherPost = new TestRows(userMapper, postMapper).post(bob, "別の投稿", T0);
    commentMapper.insert(new Comment(otherPost, alice, "別の投稿へのコメント", T0));
    comment(bob, "1", T0);
    comment(bob, "2", T0.plusSeconds(1));
    comment(bob, "3", T0.plusSeconds(2));

    assertThat(commentMapper.findByPostId(postId, null, 2)).hasSize(2);
    assertThat(commentMapper.findByPostId(postId, null, 20)).hasSize(3);
    assertThat(commentMapper.countByPostId(postId)).isEqualTo(3);
    assertThat(commentMapper.countByPostId(otherPost)).isEqualTo(1);
  }

  @Test
  void 削除すると_見つからなくなり件数が減る() {
    long id = comment(bob, "消すコメント", T0);
    comment(alice, "残すコメント", T0);

    commentMapper.delete(id);

    assertThat(commentMapper.findById(id)).isEmpty();
    assertThat(commentMapper.countByPostId(postId)).isEqualTo(1);
  }

  @Test
  void 本文は絵文字280個まで保存できる_DBもアプリと同じく1文字と数える() {
    long id = comment(bob, "🎉".repeat(280), T0);

    assertThat(commentMapper.findById(id).orElseThrow().getContent()).isEqualTo("🎉".repeat(280));
  }

  @Test
  void 本文が281文字だとDBが弾く_アプリの入力チェックをすり抜けたときの最後の守り() {
    assertThatThrownBy(() -> comment(bob, "あ".repeat(281), T0))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
