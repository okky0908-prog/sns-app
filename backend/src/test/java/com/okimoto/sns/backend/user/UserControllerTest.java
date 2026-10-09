package com.okimoto.sns.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.post.Post;
import com.okimoto.sns.backend.post.PostMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** プロフィール（A-60）と、そのユーザーの投稿一覧（A-61）のテスト。テストごとにロールバックするので、DB にデータは残らない。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class UserControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long aliceId;
  private long bobId;
  private long carolId;

  @BeforeEach
  void setUp() {
    aliceId = createUser("p_alice", "自己紹介です\n2行目");
    bobId = createUser("p_bob", null);
    carolId = createUser("p_carol", null);
  }

  private long createUser(String username, String bio) {
    User user =
        new User(
            username,
            username + "の名前",
            username + "@example.com",
            "dummy-hash",
            OffsetDateTime.now());
    user.setBio(bio);
    userMapper.insert(user);
    return user.getId();
  }

  private void follow(long followerId, long followeeId) {
    jdbcTemplate.update(
        "INSERT INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, now())",
        followerId,
        followeeId);
  }

  /** userId のユーザーとしてログインした状態で GET する */
  private ResultActions getAs(long userId, String path) throws Exception {
    return mockMvc.perform(
        get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userId)));
  }

  @Test
  void 他人のプロフィールに_フォロー数とフォロワー数と自分がフォロー中かが出る() throws Exception {
    follow(aliceId, bobId); // アリスはボブをフォロー
    follow(bobId, aliceId); // ボブとキャロルはアリスをフォロー
    follow(carolId, aliceId);

    getAs(bobId, "/api/users/p_alice")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(aliceId))
        .andExpect(jsonPath("$.username").value("p_alice"))
        .andExpect(jsonPath("$.displayName").value("p_aliceの名前"))
        .andExpect(jsonPath("$.bio").value("自己紹介です\n2行目"))
        .andExpect(jsonPath("$.iconUrl").isEmpty())
        .andExpect(jsonPath("$.followingCount").value(1))
        .andExpect(jsonPath("$.followerCount").value(2))
        .andExpect(jsonPath("$.followedByMe").value(true))
        .andExpect(jsonPath("$.me").value(false));
  }

  @Test
  void 自分のプロフィールはmeがtrue() throws Exception {
    getAs(aliceId, "/api/users/p_alice")
        .andExpect(jsonPath("$.me").value(true))
        .andExpect(jsonPath("$.followedByMe").value(false))
        .andExpect(jsonPath("$.followingCount").value(0))
        .andExpect(jsonPath("$.followerCount").value(0));
  }

  @Test
  void ユーザー名は大文字小文字を区別しない() throws Exception {
    getAs(bobId, "/api/users/P_Alice")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("p_alice"));
    getAs(bobId, "/api/users/P_ALICE/posts").andExpect(status().isOk());
  }

  @Test
  void 存在しないユーザーは404_USER_NOT_FOUND() throws Exception {
    getAs(bobId, "/api/users/nobody_here")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("このアカウントは存在しません"));
    getAs(bobId, "/api/users/nobody_here/posts")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void 投稿一覧はそのユーザーの投稿だけが新しい順で_カーソルで続きを取れる() throws Exception {
    OffsetDateTime base = OffsetDateTime.now().minusHours(1);
    for (int i = 1; i <= 23; i++) {
      postMapper.insert(new Post(aliceId, "アリス" + i, base.plusSeconds(i)));
    }
    postMapper.insert(new Post(bobId, "ボブの投稿", base.plusSeconds(100)));

    JsonNode first = read(getAs(carolId, "/api/users/p_alice/posts"));
    assertThat(first.get("items")).hasSize(20);
    assertThat(first.get("hasNext").asBoolean()).isTrue();
    JsonNode second =
        read(
            getAs(
                carolId, "/api/users/p_alice/posts?cursor=" + first.get("nextCursor").asString()));
    assertThat(second.get("hasNext").asBoolean()).isFalse();

    List<String> contents = new ArrayList<>();
    first.get("items").forEach(item -> contents.add(item.get("content").asString()));
    second.get("items").forEach(item -> contents.add(item.get("content").asString()));
    List<String> expected = new ArrayList<>();
    for (int i = 23; i >= 1; i--) {
      expected.add("アリス" + i);
    }
    assertThat(contents).containsExactlyElementsOf(expected);
  }

  @Test
  void 投稿一覧にはいいね数とコメント数と自分の投稿かが含まれる() throws Exception {
    Post post = new Post(aliceId, "アリスの投稿", OffsetDateTime.now());
    postMapper.insert(post);
    jdbcTemplate.update(
        "INSERT INTO likes (post_id, user_id, created_at) VALUES (?, ?, now())",
        post.getId(),
        bobId);

    getAs(bobId, "/api/users/p_alice/posts")
        .andExpect(jsonPath("$.items[0].likeCount").value(1))
        .andExpect(jsonPath("$.items[0].likedByMe").value(true))
        .andExpect(jsonPath("$.items[0].commentCount").value(0))
        .andExpect(jsonPath("$.items[0].mine").value(false));
    getAs(aliceId, "/api/users/p_alice/posts").andExpect(jsonPath("$.items[0].mine").value(true));
  }

  @Test
  void 未ログインは401() throws Exception {
    mockMvc.perform(get("/api/users/p_alice")).andExpect(status().isUnauthorized());
  }

  private JsonNode read(ResultActions result) throws Exception {
    return objectMapper.readTree(
        result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }
}
