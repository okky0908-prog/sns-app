package com.okimoto.sns.backend.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** コメント（A-30〜A-32）と、投稿の commentCount のテスト。テストごとにロールバックするので、DB にデータは残らない。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class CommentControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long aliceId;
  private long bobId;
  private long postId;

  @BeforeEach
  void setUp() {
    aliceId = createUser("c_alice");
    bobId = createUser("c_bob");
    Post post = new Post(aliceId, "アリスの投稿", OffsetDateTime.now());
    postMapper.insert(post);
    postId = post.getId();
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

  private ResultActions comment(long userId, long targetPostId, String content) throws Exception {
    return mockMvc.perform(
        as(userId, post("/api/posts/{id}/comments", targetPostId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("content", content))));
  }

  private long commentAndGetId(long userId, String content) throws Exception {
    String json =
        comment(userId, postId, content)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("id").asLong();
  }

  private JsonNode list(long userId, String cursor) throws Exception {
    MockHttpServletRequestBuilder request = as(userId, get("/api/posts/{id}/comments", postId));
    if (cursor != null) {
      request.param("cursor", cursor);
    }
    String json =
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json);
  }

  private ResultActions deleteComment(long userId, long commentId) throws Exception {
    return mockMvc.perform(as(userId, delete("/api/comments/{id}", commentId)));
  }

  // ===== A-31 投稿 =====

  @Test
  void 他人の投稿にコメントでき_前後の空白を除いた本文と投稿後のコメント数が返る() throws Exception {
    comment(bobId, postId, "  わかりやすいです！\n ")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.content").value("わかりやすいです！"))
        .andExpect(jsonPath("$.author.id").value(bobId))
        .andExpect(jsonPath("$.author.username").value("c_bob"))
        .andExpect(jsonPath("$.author.displayName").value("c_bobの名前"))
        .andExpect(jsonPath("$.mine").value(true))
        .andExpect(jsonPath("$.commentCount").value(1));
    // 自分の投稿にもコメントできる。ほかの人のコメントも数に入る
    comment(aliceId, postId, "ありがとう").andExpect(jsonPath("$.commentCount").value(2));
  }

  @Test
  void 空と281文字は400_280文字と絵文字は1文字として数える() throws Exception {
    comment(bobId, postId, "  \n ")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("content"))
        .andExpect(jsonPath("$.errors[0].message").value("コメントは1〜280文字で入力してください"));
    comment(bobId, postId, "あ".repeat(281)).andExpect(status().isBadRequest());
    comment(bobId, postId, "あ".repeat(280)).andExpect(status().isCreated());
    comment(bobId, postId, "😀".repeat(280)).andExpect(status().isCreated());
  }

  @Test
  void 存在しない投稿へのコメントと一覧は404_POST_NOT_FOUND() throws Exception {
    comment(bobId, 999_999_999L, "コメント")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    mockMvc
        .perform(as(bobId, get("/api/posts/{id}/comments", 999_999_999L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  @Test
  void 未ログインは401() throws Exception {
    mockMvc.perform(get("/api/posts/{id}/comments", postId)).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/comments/{id}", 1)).andExpect(status().isUnauthorized());
  }

  // ===== A-30 一覧 =====

  @Test
  void 一覧は古い順で_自分のコメントにだけmineが付く() throws Exception {
    commentAndGetId(bobId, "1つ目");
    commentAndGetId(aliceId, "2つ目");
    JsonNode res = list(aliceId, null);
    assertThat(res.get("items")).hasSize(2);
    assertThat(res.get("items").get(0).get("content").asString()).isEqualTo("1つ目");
    assertThat(res.get("items").get(0).get("mine").asBoolean()).isFalse();
    assertThat(res.get("items").get(1).get("content").asString()).isEqualTo("2つ目");
    assertThat(res.get("items").get(1).get("mine").asBoolean()).isTrue();
    assertThat(res.get("hasNext").asBoolean()).isFalse();
    assertThat(res.get("nextCursor").isNull()).isTrue();
  }

  @Test
  void 二十一件以上はカーソルで続きを取れ_途中で増えても重複も抜けもない() throws Exception {
    for (int i = 1; i <= 25; i++) {
      commentAndGetId(i % 2 == 0 ? aliceId : bobId, "コメント" + i);
    }
    JsonNode first = list(bobId, null);
    assertThat(first.get("items")).hasSize(CommentService.PAGE_SIZE);
    assertThat(first.get("hasNext").asBoolean()).isTrue();

    // 1ページ目を読んだあとに増えたコメントも、続きの最後に出る
    commentAndGetId(aliceId, "コメント26");
    JsonNode second = list(bobId, first.get("nextCursor").asString());
    assertThat(second.get("hasNext").asBoolean()).isFalse();

    List<String> contents = new ArrayList<>();
    first.get("items").forEach(item -> contents.add(item.get("content").asString()));
    second.get("items").forEach(item -> contents.add(item.get("content").asString()));
    List<String> expected = new ArrayList<>();
    for (int i = 1; i <= 26; i++) {
      expected.add("コメント" + i);
    }
    assertThat(contents).containsExactlyElementsOf(expected);
  }

  @Test
  void 形式の違うカーソルは400() throws Exception {
    mockMvc
        .perform(as(bobId, get("/api/posts/{id}/comments", postId)).param("cursor", "zzz"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("cursor"));
  }

  // ===== A-32 削除 =====

  @Test
  void 自分のコメントは削除でき_削除後のコメント数が返る() throws Exception {
    long first = commentAndGetId(bobId, "消すコメント");
    commentAndGetId(aliceId, "残るコメント");
    deleteComment(bobId, first)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commentCount").value(1));
    assertThat(list(bobId, null).get("items")).hasSize(1);
    // もう一度消そうとすると 404
    deleteComment(bobId, first)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
  }

  @Test
  void 他人のコメントは投稿者でも削除できず403() throws Exception {
    long bobsComment = commentAndGetId(bobId, "ボブのコメント");
    // アリスは投稿者だが、ボブのコメントは消せない
    deleteComment(aliceId, bobsComment)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertThat(list(aliceId, null).get("items")).hasSize(1);
  }

  // ===== 投稿との関係 =====

  @Test
  void 投稿詳細とタイムラインにコメント数が出る() throws Exception {
    commentAndGetId(bobId, "1");
    commentAndGetId(aliceId, "2");
    mockMvc
        .perform(as(bobId, get("/api/posts/{id}", postId)))
        .andExpect(jsonPath("$.commentCount").value(2));
    mockMvc
        .perform(as(aliceId, get("/api/timeline")))
        .andExpect(jsonPath("$.items[0].id").value(postId))
        .andExpect(jsonPath("$.items[0].commentCount").value(2));
  }

  @Test
  void 投稿を削除すると_その投稿のコメントも消える() throws Exception {
    commentAndGetId(bobId, "コメント");
    mockMvc
        .perform(as(aliceId, delete("/api/posts/{id}", postId)))
        .andExpect(status().isNoContent());
    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM comments WHERE post_id = ?", Integer.class, postId);
    assertThat(rows).isZero();
  }

  @Test
  void 投稿直後の一覧にも出る() throws Exception {
    commentAndGetId(bobId, "すぐ出る");
    mockMvc
        .perform(as(aliceId, get("/api/posts/{id}/comments", postId)))
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].author.username").value("c_bob"));
  }
}
