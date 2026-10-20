package com.okimoto.sns.backend.user;

import static com.okimoto.sns.backend.support.TestRows.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.okimoto.sns.backend.follow.FollowMapper;
import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.support.MapperTest;
import com.okimoto.sns.backend.support.TestRows;
import com.okimoto.sns.backend.web.CreatedAtCursor;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * UserMapper の SQL（11本）。
 *
 * <ul>
 *   <li>同値分割：見つかる・見つからない、自分・フォロー中の人・フォローしていない人
 *   <li>境界値分析：ユーザー名 3・4・15・16文字、表示名 50・51文字（絵文字も1文字）、自己紹介 160・161文字、カーソルの境目
 *   <li>エラー推測：ユーザー名の大文字小文字、LIKE の特殊文字（{@code _ % \}）、メールアドレスの大文字
 * </ul>
 */
@MapperTest
class UserMapperTest {

  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;
  @Autowired private FollowMapper followMapper;

  private TestRows rows;

  @BeforeEach
  void setUp() {
    rows = new TestRows(userMapper, postMapper);
  }

  private User newUser(String username, String displayName, String email) {
    return new User(username, displayName, email, "dummy-hash", T0);
  }

  @Nested
  class 登録と取得 {

    @Test
    void 登録すると_IDが振られ_同じ内容で取れる() {
      User user = newUser("Yamada_01", "山田太郎", "yamada@example.com");
      userMapper.insert(user);

      User found = userMapper.findById(user.getId()).orElseThrow();

      assertThat(found.getUsername()).isEqualTo("Yamada_01");
      assertThat(found.getDisplayName()).isEqualTo("山田太郎");
      assertThat(found.getEmail()).isEqualTo("yamada@example.com");
      assertThat(found.getPasswordHash()).isEqualTo("dummy-hash");
      assertThat(found.getBio()).isNull();
      assertThat(found.getIconKey()).isNull();
      assertThat(found.getCreatedAt()).isEqualTo(T0);
      // 行をロックして読む版も、同じ行を返す（ロックそのものの確認は、同時の更新を扱う結合テストで行う）
      assertThat(userMapper.findByIdForUpdate(user.getId()).orElseThrow().getUsername())
          .isEqualTo("Yamada_01");
    }

    @Test
    void ないユーザーは見つからない() {
      assertThat(userMapper.findById(Long.MAX_VALUE)).isEmpty();
      assertThat(userMapper.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void メールアドレスは保存した小文字で探す_大文字のままでは見つからない() {
      rows.user("alice");

      assertThat(userMapper.findByEmail("alice@example.com")).isPresent();
      assertThat(userMapper.existsByEmail("alice@example.com")).isTrue();
      // 呼ぶ側（AuthService）が小文字にしてから渡す決まり
      assertThat(userMapper.findByEmail("ALICE@example.com")).isEmpty();
      assertThat(userMapper.existsByEmail("bob@example.com")).isFalse();
    }

    @Test
    void ユーザー名は大文字小文字を区別せずに探す() {
      long id = rows.user("Alice");

      assertThat(userMapper.existsByUsernameIgnoreCase("aLiCe")).isTrue();
      assertThat(userMapper.findIdByUsernameIgnoreCase("ALICE")).hasValue(id);
      assertThat(userMapper.existsByUsernameIgnoreCase("alic")).isFalse();
      assertThat(userMapper.findIdByUsernameIgnoreCase("bobby")).isEmpty();
    }
  }

  @Nested
  class DBの制約_アプリの入力チェックをすり抜けたときの最後の守り {

    @Test
    void 大文字小文字だけが違うユーザー名は登録できない() {
      rows.user("Alice");

      assertThatThrownBy(() -> userMapper.insert(newUser("alice", "別人", "other@example.com")))
          .isInstanceOf(DuplicateKeyException.class)
          .hasMessageContaining("uq_users_username_lower");
    }

    @Test
    void 同じメールアドレスは登録できない() {
      rows.user("alice");

      assertThatThrownBy(() -> userMapper.insert(newUser("other", "別人", "alice@example.com")))
          .isInstanceOf(DuplicateKeyException.class)
          .hasMessageContaining("uq_users_email");
    }

    @Test
    void メールアドレスは小文字でなければ登録できない() {
      assertThatThrownBy(() -> userMapper.insert(newUser("alice", "名前", "Alice@example.com")))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_users_email_lowercase");
    }

    @ParameterizedTest(name = "ユーザー名「{0}」は登録できない")
    @ValueSource(strings = {"abc", "a234567890123456", "ab-c", "あいうえ"})
    void ユーザー名は半角英数字と_で4から15文字(String username) {
      assertThatThrownBy(
              () -> userMapper.insert(newUser(username, "名前", "u" + username.length() + "@x.com")))
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest(name = "ユーザー名「{0}」は登録できる")
    @ValueSource(strings = {"abcd", "a23456789012345", "a_b_"})
    void ユーザー名の境界の内側は登録できる(String username) {
      User user = newUser(username, "名前", "u" + username.length() + "@x.com");
      userMapper.insert(user);

      assertThat(user.getId()).isNotNull();
    }

    @Test
    void 表示名は絵文字50個まで保存でき_51文字は弾く() {
      User emoji = newUser("emoji", "🎉".repeat(50), "emoji@example.com");
      userMapper.insert(emoji);

      assertThat(userMapper.findById(emoji.getId()).orElseThrow().getDisplayName())
          .isEqualTo("🎉".repeat(50));
      assertThatThrownBy(() -> userMapper.insert(newUser("long", "あ".repeat(51), "l@x.com")))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Nested
  class プロフィール {

    @Test
    void フォロー数とフォロワー数と_見ている人がフォロー中かを1回で取る() {
      long alice = rows.user("alice");
      long bob = rows.user("bobby");
      long carol = rows.user("carol");
      followMapper.insert(alice, bob, T0); // alice → bob
      followMapper.insert(alice, carol, T0); // alice → carol
      followMapper.insert(carol, alice, T0); // carol → alice

      Profile seenByCarol = userMapper.findProfile("ALICE", carol).orElseThrow();
      Profile seenByBob = userMapper.findProfile("alice", bob).orElseThrow();

      assertThat(seenByCarol.getId()).isEqualTo(alice);
      assertThat(seenByCarol.getFollowingCount()).isEqualTo(2);
      assertThat(seenByCarol.getFollowerCount()).isEqualTo(1);
      assertThat(seenByCarol.isFollowedByMe()).isTrue();
      assertThat(seenByBob.isFollowedByMe()).isFalse();
    }

    @Test
    void ないユーザーのプロフィールは見つからない() {
      assertThat(userMapper.findProfile("nobody", 0)).isEmpty();
    }

    @Test
    void アイコンを渡さなければ今のアイコンのまま_渡せば差し替える() {
      long id = rows.user("alice");
      userMapper.updateProfile(id, "アリス", "自己紹介", "icons/first.png", T0.plusMinutes(1));

      userMapper.updateProfile(id, "アリス2", null, null, T0.plusMinutes(2));

      User kept = userMapper.findById(id).orElseThrow();
      assertThat(kept.getDisplayName()).isEqualTo("アリス2");
      assertThat(kept.getBio()).isNull(); // 自己紹介は null で上書きする（空にした）
      assertThat(kept.getIconKey()).isEqualTo("icons/first.png");
      assertThat(kept.getUpdatedAt()).isEqualTo(T0.plusMinutes(2));

      userMapper.updateProfile(id, "アリス2", null, "icons/second.png", T0.plusMinutes(3));
      assertThat(userMapper.findById(id).orElseThrow().getIconKey()).isEqualTo("icons/second.png");
    }

    @Test
    void 自己紹介は160文字まで保存でき_161文字は弾く() {
      long id = rows.user("alice");

      userMapper.updateProfile(id, "アリス", "あ".repeat(160), null, T0);
      assertThat(userMapper.findById(id).orElseThrow().getBio()).hasSize(160);
      assertThatThrownBy(() -> userMapper.updateProfile(id, "アリス", "あ".repeat(161), null, T0))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Nested
  class 検索 {

    private List<String> search(String keyword, long me, SearchCursor cursor, int limit) {
      return userMapper
          .search(keyword, UserSearchService.escapeLike(keyword), me, cursor, limit)
          .stream()
          .map(FoundUser::getUsername)
          .toList();
    }

    @Test
    void 完全一致_前方一致_それ以外の順で_同じ順位ならユーザー名の順() {
      rows.user("zz_yama"); // 途中に含む → 2
      rows.user("yamada"); // 前方一致 → 1
      rows.user("Yama"); // 完全一致（大文字小文字を区別しない） → 0
      rows.user("ayama"); // 途中に含む → 2
      User byDisplayName = newUser("tanaka", "山田Yamaさん", "tanaka@example.com");
      userMapper.insert(byDisplayName); // 表示名に含む → 2

      assertThat(search("yama", 0, null, 20))
          .containsExactly("Yama", "yamada", "ayama", "tanaka", "zz_yama");
    }

    @Test
    void 一致しなければ空() {
      rows.user("alice");

      assertThat(search("nobody", 0, null, 20)).isEmpty();
    }

    @Test
    void アンダースコアは任意の1文字ではなく_そのままの文字として探す() {
      rows.user("a_bc");
      rows.user("axbc");

      assertThat(search("a_b", 0, null, 20)).containsExactly("a_bc");
    }

    @Test
    void パーセントと円記号もそのままの文字として探す() {
      userMapper.insert(newUser("percent", "達成率100%", "p@example.com"));
      userMapper.insert(newUser("hundred", "1000人", "h@example.com"));
      userMapper.insert(newUser("backsl", "C:\\temp", "b@example.com"));

      assertThat(search("100%", 0, null, 20)).containsExactly("percent");
      assertThat(search("\\", 0, null, 20)).containsExactly("backsl");
    }

    @Test
    void 続きはカーソルより後ろだけで_limitまで() {
      rows.user("yama");
      rows.user("yamaa");
      rows.user("yamab");
      rows.user("ayama");

      assertThat(search("yama", 0, null, 2)).containsExactly("yama", "yamaa");
      // カーソル = 前のページの最後の行（前方一致 1・yamaa）
      assertThat(search("yama", 0, new SearchCursor(1, "yamaa"), 20))
          .containsExactly("yamab", "ayama");
    }

    @Test
    void 各行の自分がフォロー中かは_見ている人の目から見た値() {
      long me = rows.user("watcher");
      long followed = rows.user("yamada");
      rows.user("yamato");
      followMapper.insert(me, followed, T0);

      List<FoundUser> found = userMapper.search("yama", "yama", me, null, 20);

      assertThat(found)
          .extracting(FoundUser::getUsername, FoundUser::isFollowedByMe)
          .containsExactly(tuple("yamada", true), tuple("yamato", false));
    }
  }

  @Nested
  class 最近参加したユーザー {

    @Test
    void 登録日時の新しい順で_同じ日時ならIDの大きい順() {
      OffsetDateTime later = T0.plusSeconds(10);
      long oldest = rows.user("old1", T0);
      long same1 = rows.user("same1", later);
      long same2 = rows.user("same2", later);

      List<Long> ids = userMapper.findRecent(0, null, 20).stream().map(FoundUser::getId).toList();

      assertThat(ids).containsExactly(same2, same1, oldest);
    }

    @Test
    void 続きはカーソルの行より古いものだけで_limitまで() {
      OffsetDateTime later = T0.plusSeconds(10);
      long oldest = rows.user("old1", T0);
      long same1 = rows.user("same1", later);
      long same2 = rows.user("same2", later);

      assertThat(userMapper.findRecent(0, null, 1)).hasSize(1);
      assertThat(
              userMapper.findRecent(0, new CreatedAtCursor(later, same2), 20).stream()
                  .map(FoundUser::getId))
          .containsExactly(same1, oldest);
    }
  }
}
