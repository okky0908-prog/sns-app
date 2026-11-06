package com.okimoto.sns.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.okimoto.sns.backend.support.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * ユーザー検索の部分一致（ILIKE '%…%'）が、全件を読まずに索引（pg_trgm の GIN）で引けることのテスト（Issue #131）。
 *
 * <p>部分一致は B-tree の索引を使えないので、索引がないと users を全件読み、ユーザーの数に比例して遅くなる （docs/performance-test-results.md）。
 *
 * <ul>
 *   <li>同値分割：英字のキーワード・日本語のキーワード・大文字を含むキーワード（ILIKE）。どれも 3 文字以上（trigram は 3 文字の組なので、2
 *       文字以下では索引で絞り込めない）
 * </ul>
 *
 * <p>テストの DB は行が少なく、索引があっても全件を読むほうが速いと判断されるので、enable_seqscan を切って「索引を使える形か」を確かめる。 WHERE は
 * UserMapper.xml の search と同じ形にする（変えたら、ここも合わせる）。
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@ActiveProfiles("test")
class UserSearchIndexTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  private String explainSearch(String pattern) {
    jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
    List<String> plan =
        jdbcTemplate.queryForList(
            "EXPLAIN SELECT u.id FROM users u"
                + " WHERE u.username ILIKE '%' || ? || '%'"
                + " OR u.display_name ILIKE '%' || ? || '%'",
            String.class, pattern, pattern);
    return String.join("\n", plan);
  }

  @ParameterizedTest
  @ValueSource(strings = {"yama", "性能テスト", "YaMa"})
  void 三文字以上のキーワードでは_ユーザー名と表示名の両方を索引で引く(String keyword) {
    String plan = explainSearch(keyword);

    assertThat(plan)
        .contains("idx_users_username_trgm")
        .contains("idx_users_display_name_trgm")
        .doesNotContain("Seq Scan");
  }
}
