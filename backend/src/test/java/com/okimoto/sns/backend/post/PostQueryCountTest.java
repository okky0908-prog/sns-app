package com.okimoto.sns.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.support.SqlCounter;
import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 一覧 API で N+1 問題が起きていないことのテスト（docs/database.md「N+1 問題を防ぐ」）。
 *
 * <p>投稿が2件のときと20件（投稿者も20人）のときで、1回の取得で DB に送る SQL の数が同じであることを確かめる。 投稿ごと・投稿者ごとに SQL
 * を発行する書き方をすると、件数に比例して SQL が増えるので失敗する。
 *
 * <p>いいね・コメント・画像などを追加して SQL の数そのものが増えるのはよい（件数によって変わらなければよい）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(SqlCounter.Config.class)
class PostQueryCountTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SqlCounter sqlCounter;

  private long viewerId;
  private int userSeq;

  @BeforeEach
  void setUp() {
    // 動作確認用のデータなどが DB に残っていても件数をそろえられるよう、投稿を消しておく（テストの終わりにロールバックされる）
    jdbcTemplate.update("DELETE FROM posts");
    viewerId = createUser();
  }

  private long createUser() {
    userSeq++;
    String username = "q_user" + userSeq;
    User user =
        new User(username, username, username + "@example.com", "dummy-hash", OffsetDateTime.now());
    userMapper.insert(user);
    return user.getId();
  }

  /** 別々の投稿者による投稿を count 件作り、閲覧者がその全員をフォローする。いいね数・いいね済みかの集計も確かめるため、 投稿者と閲覧者が各投稿にいいねしておく */
  private void createPostsByDifferentAuthors(int count) {
    for (int i = 0; i < count; i++) {
      long authorId = createUser();
      Post post = new Post(authorId, "投稿" + i, OffsetDateTime.now().plusNanos(i * 1000L));
      postMapper.insert(post);
      jdbcTemplate.update(
          "INSERT INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, now())",
          viewerId,
          authorId);
      for (long likerId : new long[] {authorId, viewerId}) {
        jdbcTemplate.update(
            "INSERT INTO likes (post_id, user_id, created_at) VALUES (?, ?, now())",
            post.getId(),
            likerId);
      }
    }
  }

  private record Result(int sqlCount, JsonNode body) {}

  /** API を1回呼び、その間に DB に送った SQL の数を返す */
  private Result fetch(String path) throws Exception {
    sqlCounter.reset();
    String json =
        mockMvc
            .perform(
                get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(viewerId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new Result(sqlCounter.count(), objectMapper.readTree(json));
  }

  /** 2件のときと20件のときで SQL の数が同じか（それぞれの件数も確かめる） */
  private void assertSqlCountDoesNotGrow(String path) throws Exception {
    createPostsByDifferentAuthors(2);
    Result few = fetch(path);
    assertThat(few.body().get("items")).hasSize(2);

    createPostsByDifferentAuthors(PostService.PAGE_SIZE + 5);
    Result many = fetch(path);
    assertThat(many.body().get("items")).hasSize(PostService.PAGE_SIZE);
    assertThat(many.body().get("items").get(0).get("likeCount").asLong()).isEqualTo(2);
    assertThat(many.body().get("items").get(0).get("likedByMe").asBoolean()).isTrue();

    assertThat(many.sqlCount())
        .as(
            "%s の SQL の数（2件：%d回、%d件：%d回）",
            path, few.sqlCount(), PostService.PAGE_SIZE, many.sqlCount())
        .isEqualTo(few.sqlCount());

    // 続きのページ（カーソル付き）でも同じ
    Result next = fetch(path + "?cursor=" + many.body().get("nextCursor").asString());
    assertThat(next.body().get("items").size()).isPositive();
    assertThat(next.sqlCount()).isEqualTo(few.sqlCount());
  }

  @Test
  void 全体タイムラインは件数によらずSQLの数が同じ() throws Exception {
    assertSqlCountDoesNotGrow("/api/timeline/all");
  }

  @Test
  void フォロー中タイムラインは件数によらずSQLの数が同じ() throws Exception {
    assertSqlCountDoesNotGrow("/api/timeline");
  }

  @Test
  void 今のタイムラインは投稿と投稿者といいねを1回のSQLで取っている() throws Exception {
    createPostsByDifferentAuthors(PostService.PAGE_SIZE);
    // 今は1回（いいね・画像などの実装で増えたら、この数を見直す。件数で変わらないことは上のテストで確かめる）
    assertThat(fetch("/api/timeline/all").sqlCount()).isEqualTo(1);
    assertThat(fetch("/api/timeline").sqlCount()).isEqualTo(1);
  }

  @Test
  void 新しい投稿の件数の確認は件数によらず1回() throws Exception {
    List<Integer> counts = new ArrayList<>();
    for (int n : new int[] {2, 30}) {
      createPostsByDifferentAuthors(n);
      sqlCounter.reset();
      mockMvc
          .perform(
              get("/api/timeline/all/new-count")
                  .param("since", "0")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(viewerId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.count").isNumber());
      counts.add(sqlCounter.count());
    }
    assertThat(counts).containsOnly(1);
  }
}
