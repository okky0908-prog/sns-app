package com.okimoto.sns.backend.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.post.Post;
import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** フォロー（A-50・A-51）のテスト。テストごとにロールバックするので、DB にデータは残らない。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class FollowControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long aliceId;
  private long bobId;
  private long carolId;

  @BeforeEach
  void setUp() {
    aliceId = createUser("f_alice");
    bobId = createUser("f_bob");
    carolId = createUser("f_carol");
  }

  private long createUser(String username) {
    User user =
        new User(username, username, username + "@example.com", "dummy-hash", OffsetDateTime.now());
    userMapper.insert(user);
    return user.getId();
  }

  private MockHttpServletRequestBuilder as(long userId, MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userId));
  }

  private ResultActions follow(long userId, String username) throws Exception {
    return mockMvc.perform(as(userId, post("/api/users/{username}/follow", username)));
  }

  private ResultActions unfollow(long userId, String username) throws Exception {
    return mockMvc.perform(as(userId, delete("/api/users/{username}/follow", username)));
  }

  private int followRows(long followerId, long followeeId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
        Integer.class,
        followerId,
        followeeId);
  }

  @Test
  void フォローするとフォロワー数が1増え_解除すると元に戻る() throws Exception {
    follow(aliceId, "f_bob")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true))
        .andExpect(jsonPath("$.followerCount").value(1));
    unfollow(aliceId, "f_bob")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(false))
        .andExpect(jsonPath("$.followerCount").value(0));
  }

  @Test
  void 何度フォローしても1行で_何度解除してもエラーにならない() throws Exception {
    follow(aliceId, "f_bob");
    follow(aliceId, "f_bob")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true))
        .andExpect(jsonPath("$.followerCount").value(1));
    assertThat(followRows(aliceId, bobId)).isEqualTo(1);

    unfollow(aliceId, "f_bob");
    unfollow(aliceId, "f_bob")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(false));
    assertThat(followRows(aliceId, bobId)).isZero();
  }

  @Test
  void ほかの人のフォローもフォロワー数に入る() throws Exception {
    follow(carolId, "f_bob");
    follow(aliceId, "f_bob").andExpect(jsonPath("$.followerCount").value(2));
    // アリスが解除しても、キャロルのフォローは残る
    unfollow(aliceId, "f_bob")
        .andExpect(jsonPath("$.following").value(false))
        .andExpect(jsonPath("$.followerCount").value(1));
  }

  @Test
  void ユーザー名は大文字小文字を区別しない() throws Exception {
    follow(aliceId, "F_BOB")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true));
    assertThat(followRows(aliceId, bobId)).isEqualTo(1);
  }

  @Test
  void 自分自身は400_CANNOT_FOLLOW_SELF() throws Exception {
    follow(aliceId, "f_alice")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CANNOT_FOLLOW_SELF"))
        .andExpect(jsonPath("$.message").value("自分自身はフォローできません"));
    assertThat(followRows(aliceId, aliceId)).isZero();
  }

  @Test
  void 存在しないユーザーは404_USER_NOT_FOUND() throws Exception {
    follow(aliceId, "nobody_here")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    unfollow(aliceId, "nobody_here")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void 未ログインは401() throws Exception {
    mockMvc.perform(post("/api/users/{u}/follow", "f_bob")).andExpect(status().isUnauthorized());
  }

  @Test
  void フォローすると相手の過去の投稿もフォロー中タイムラインに出て_解除すると消える() throws Exception {
    Post bobsPost = new Post(bobId, "ボブの過去の投稿", OffsetDateTime.now().minusDays(1));
    postMapper.insert(bobsPost);

    follow(aliceId, "f_bob");
    mockMvc
        .perform(as(aliceId, get("/api/timeline")))
        .andExpect(jsonPath("$.items[0].id").value(bobsPost.getId()));

    unfollow(aliceId, "f_bob");
    mockMvc
        .perform(as(aliceId, get("/api/timeline")))
        .andExpect(jsonPath("$.items.length()").value(0));
  }
}
