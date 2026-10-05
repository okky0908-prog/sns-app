package com.okimoto.sns.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 投稿（A-11〜A-14）とタイムライン（A-10・A-15）のテスト。テストごとにロールバックするので、DB にデータは残らない。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class PostControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long aliceId;
  private long bobId;
  private long carolId;

  @BeforeEach
  void setUp() {
    aliceId = createUser("t_alice");
    bobId = createUser("t_bob");
    carolId = createUser("t_carol");
  }

  // ===== 道具 =====

  private long createUser(String username) {
    User user =
        new User(
            username,
            username + "の名前",
            username + "@example.com",
            "dummy-hash",
            OffsetDateTime.now());
    userMapper.insert(user);
    return user.getId();
  }

  private MockHttpServletRequestBuilder as(long userId, MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userId));
  }

  private ResultActions createPost(long userId, String content) throws Exception {
    return mockMvc.perform(
        as(userId, post("/api/posts"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("content", content))));
  }

  private long createPostAndGetId(long userId, String content) throws Exception {
    String json =
        createPost(userId, content)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("id").asLong();
  }

  private ResultActions updatePost(long userId, long postId, String content) throws Exception {
    return mockMvc.perform(
        as(userId, put("/api/posts/{id}", postId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("content", content))));
  }

  private void follow(long followerId, long followeeId) {
    jdbcTemplate.update(
        "INSERT INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, now())",
        followerId,
        followeeId);
  }

  // ===== A-11 作成 =====

  @Test
  void create_本文を投稿でき_前後の空白は取り除かれる() throws Exception {
    createPost(aliceId, "  はじめての投稿です\n2行目  ")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(notNullValue()))
        .andExpect(jsonPath("$.content").value("はじめての投稿です\n2行目"))
        .andExpect(jsonPath("$.images", hasSize(0)))
        .andExpect(jsonPath("$.author.id").value(aliceId))
        .andExpect(jsonPath("$.author.username").value("t_alice"))
        .andExpect(jsonPath("$.author.displayName").value("t_aliceの名前"))
        .andExpect(jsonPath("$.editedAt").value(nullValue()))
        .andExpect(jsonPath("$.createdAt").value(notNullValue()))
        .andExpect(jsonPath("$.mine").value(true));
  }

  @Test
  void create_本文は1から280文字_絵文字も1文字と数える() throws Exception {
    createPost(aliceId, "   ")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("content"))
        .andExpect(jsonPath("$.errors[0].message").value("本文は1〜280文字で入力してください"));
    createPost(aliceId, "あ".repeat(281)).andExpect(status().isBadRequest());
    createPost(aliceId, "あ".repeat(280)).andExpect(status().isCreated());
    createPost(aliceId, "😀".repeat(280)).andExpect(status().isCreated());
  }

  @Test
  void ログインしていないと投稿もタイムラインも401() throws Exception {
    mockMvc
        .perform(
            post("/api/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/timeline")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/timeline/all")).andExpect(status().isUnauthorized());
  }

  // ===== A-12 詳細 =====

  @Test
  void get_詳細を取得でき_他人の投稿はmineがfalse() throws Exception {
    long postId = createPostAndGetId(aliceId, "詳細のテスト");
    mockMvc
        .perform(as(aliceId, get("/api/posts/{id}", postId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("詳細のテスト"))
        .andExpect(jsonPath("$.mine").value(true));
    mockMvc
        .perform(as(bobId, get("/api/posts/{id}", postId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mine").value(false));
  }

  @Test
  void get_存在しない投稿は404_IDが数字でなければ400() throws Exception {
    mockMvc
        .perform(as(aliceId, get("/api/posts/{id}", 999_999_999L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").value("この投稿は見つかりません"));
    mockMvc
        .perform(as(aliceId, get("/api/posts/abc")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("postId"));
  }

  // ===== A-13 編集 =====

  @Test
  void update_本人は本文を編集でき_編集済みになる() throws Exception {
    long postId = createPostAndGetId(aliceId, "編集前");
    updatePost(aliceId, postId, "編集後")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("編集後"))
        .andExpect(jsonPath("$.editedAt").value(notNullValue()));
  }

  @Test
  void update_本文が変わらなければ編集済みにしない() throws Exception {
    long postId = createPostAndGetId(aliceId, "同じ本文");
    updatePost(aliceId, postId, "  同じ本文  ")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.editedAt").value(nullValue()));
  }

  @Test
  void update_他人の投稿は403_存在しない投稿は404_本文が空なら400() throws Exception {
    long postId = createPostAndGetId(aliceId, "アリスの投稿");
    updatePost(bobId, postId, "書き換え")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("この操作は実行できません"));
    updatePost(aliceId, 999_999_999L, "書き換え").andExpect(status().isNotFound());
    updatePost(aliceId, postId, "").andExpect(status().isBadRequest());
    // 他人の編集は反映されていない
    mockMvc
        .perform(as(aliceId, get("/api/posts/{id}", postId)))
        .andExpect(jsonPath("$.content").value("アリスの投稿"));
  }

  // ===== A-14 削除 =====

  @Test
  void delete_本人は削除でき_その後は404() throws Exception {
    long postId = createPostAndGetId(aliceId, "消す投稿");
    mockMvc
        .perform(as(aliceId, delete("/api/posts/{id}", postId)))
        .andExpect(status().isNoContent());
    mockMvc.perform(as(aliceId, get("/api/posts/{id}", postId))).andExpect(status().isNotFound());
    mockMvc
        .perform(as(aliceId, delete("/api/posts/{id}", postId)))
        .andExpect(status().isNotFound());
  }

  @Test
  void delete_他人の投稿は403で削除されない() throws Exception {
    long postId = createPostAndGetId(aliceId, "アリスの投稿");
    mockMvc.perform(as(bobId, delete("/api/posts/{id}", postId))).andExpect(status().isForbidden());
    mockMvc.perform(as(aliceId, get("/api/posts/{id}", postId))).andExpect(status().isOk());
  }

  // ===== A-10・A-15 タイムライン =====

  @Test
  void フォロー中タイムラインは自分とフォロー中の人の投稿だけ_全体は全員() throws Exception {
    createPostAndGetId(aliceId, "アリスの投稿");
    createPostAndGetId(bobId, "ボブの投稿");
    createPostAndGetId(carolId, "キャロルの投稿");
    follow(aliceId, bobId); // アリスはボブだけをフォロー

    mockMvc
        .perform(as(aliceId, get("/api/timeline")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.items[?(@.content == 'アリスの投稿')]").exists())
        .andExpect(jsonPath("$.items[?(@.content == 'ボブの投稿')]").exists())
        .andExpect(jsonPath("$.items[?(@.content == 'キャロルの投稿')]").doesNotExist());

    mockMvc
        .perform(as(aliceId, get("/api/timeline/all")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.content == 'キャロルの投稿')]").exists());

    // フォローは片方向：ボブのフォロー中タイムラインにアリスの投稿は出ない
    mockMvc
        .perform(as(bobId, get("/api/timeline")))
        .andExpect(jsonPath("$.items[?(@.content == 'アリスの投稿')]").doesNotExist());
  }

  @Test
  void タイムラインは新しい順に20件ずつ_次があればhasNext() throws Exception {
    for (int i = 1; i <= 21; i++) {
      createPostAndGetId(aliceId, "投稿" + i);
    }
    mockMvc
        .perform(as(aliceId, get("/api/timeline")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(20)))
        .andExpect(jsonPath("$.items[0].content").value("投稿21"))
        .andExpect(jsonPath("$.items[19].content").value("投稿2"))
        .andExpect(jsonPath("$.hasNext").value(true));
    mockMvc
        .perform(as(aliceId, get("/api/timeline").param("page", "1")))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].content").value("投稿1"))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void タイムラインのpageが負の数や数字以外なら400() throws Exception {
    mockMvc
        .perform(as(aliceId, get("/api/timeline").param("page", "-1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("page"));
    mockMvc
        .perform(as(aliceId, get("/api/timeline/all").param("page", "x")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ユーザーを削除すると投稿とフォロー関係も消える() throws Exception {
    createPostAndGetId(bobId, "ボブの投稿");
    follow(aliceId, bobId);
    // MyBatis を通さずに削除するので、結果も MyBatis のキャッシュを通さず DB を直接数えて確かめる
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", bobId);
    Integer posts =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM posts WHERE user_id = ?", Integer.class, bobId);
    Integer follows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM follows WHERE followee_id = ?", Integer.class, bobId);
    assertThat(posts).isZero();
    assertThat(follows).isZero();
  }
}
