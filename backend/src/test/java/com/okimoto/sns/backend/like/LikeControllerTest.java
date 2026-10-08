package com.okimoto.sns.backend.like;

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

/** いいね（A-40・A-41）と、投稿の likeCount・likedByMe のテスト。テストごとにロールバックするので、DB にデータは残らない。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class LikeControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long aliceId;
  private long bobId;
  private long postId;

  @BeforeEach
  void setUp() {
    aliceId = createUser("l_alice");
    bobId = createUser("l_bob");
    Post post = new Post(aliceId, "アリスの投稿", OffsetDateTime.now());
    postMapper.insert(post);
    postId = post.getId();
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

  private ResultActions like(long userId, long targetPostId) throws Exception {
    return mockMvc.perform(as(userId, post("/api/posts/{id}/likes", targetPostId)));
  }

  private ResultActions unlike(long userId, long targetPostId) throws Exception {
    return mockMvc.perform(as(userId, delete("/api/posts/{id}/likes", targetPostId)));
  }

  private int rowsInLikes() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM likes WHERE post_id = ?", Integer.class, postId);
  }

  @Test
  void いいねすると数が1増え_取り消すと元に戻る() throws Exception {
    like(bobId, postId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(1))
        .andExpect(jsonPath("$.likedByMe").value(true));
    unlike(bobId, postId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(0))
        .andExpect(jsonPath("$.likedByMe").value(false));
  }

  @Test
  void 何度いいねしても1回分で_何度取り消してもエラーにならない() throws Exception {
    like(bobId, postId).andExpect(status().isOk());
    like(bobId, postId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(1))
        .andExpect(jsonPath("$.likedByMe").value(true));
    assertThat(rowsInLikes()).isEqualTo(1);

    unlike(bobId, postId).andExpect(status().isOk());
    unlike(bobId, postId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(0))
        .andExpect(jsonPath("$.likedByMe").value(false));
    assertThat(rowsInLikes()).isZero();
  }

  @Test
  void 自分の投稿にもいいねでき_ほかの人のいいねも数に入る() throws Exception {
    like(aliceId, postId).andExpect(jsonPath("$.likeCount").value(1));
    like(bobId, postId)
        .andExpect(jsonPath("$.likeCount").value(2))
        .andExpect(jsonPath("$.likedByMe").value(true));
    // ボブが取り消しても、アリスのいいねは残る（取り消せるのは自分のいいねだけ）
    unlike(bobId, postId)
        .andExpect(jsonPath("$.likeCount").value(1))
        .andExpect(jsonPath("$.likedByMe").value(false));
  }

  @Test
  void 存在しない投稿は404_POST_NOT_FOUND() throws Exception {
    like(bobId, 999_999_999L)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    unlike(bobId, 999_999_999L)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  @Test
  void 未ログインは401() throws Exception {
    mockMvc.perform(post("/api/posts/{id}/likes", postId)).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/posts/{id}/likes", postId)).andExpect(status().isUnauthorized());
  }

  @Test
  void 投稿詳細とタイムラインに_いいね数と自分がいいね済みかが出る() throws Exception {
    like(aliceId, postId);
    like(bobId, postId);
    long carolId = createUser("l_carol");

    mockMvc
        .perform(as(bobId, get("/api/posts/{id}", postId)))
        .andExpect(jsonPath("$.likeCount").value(2))
        .andExpect(jsonPath("$.likedByMe").value(true));
    mockMvc
        .perform(as(carolId, get("/api/posts/{id}", postId)))
        .andExpect(jsonPath("$.likeCount").value(2))
        .andExpect(jsonPath("$.likedByMe").value(false));
    // アリスのフォロー中タイムラインには自分の投稿が出る
    mockMvc
        .perform(as(aliceId, get("/api/timeline")))
        .andExpect(jsonPath("$.items[0].id").value(postId))
        .andExpect(jsonPath("$.items[0].likeCount").value(2))
        .andExpect(jsonPath("$.items[0].likedByMe").value(true));
  }

  @Test
  void 投稿を削除すると_その投稿へのいいねも消える() throws Exception {
    like(bobId, postId);
    mockMvc
        .perform(as(aliceId, delete("/api/posts/{id}", postId)))
        .andExpect(status().isNoContent());
    assertThat(rowsInLikes()).isZero();
  }
}
