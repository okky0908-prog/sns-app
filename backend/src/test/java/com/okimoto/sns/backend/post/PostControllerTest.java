package com.okimoto.sns.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

/** 投稿（A-11〜A-14）とタイムライン（A-10・A-15〜A-17）のテスト。テストごとにロールバックするので、DB にデータは残らない。 */
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

  /** 投稿作成（A-11）は multipart/form-data。本文だけを送る（画像付きの投稿は PostImageTest で確かめる） */
  private ResultActions createPost(long userId, String content) throws Exception {
    return mockMvc.perform(
        multipart("/api/posts")
            .param("content", content)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userId)));
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
  void create_画像がなければ本文は1から280文字_絵文字も1文字と数える() throws Exception {
    createPost(aliceId, "   ")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("content"))
        .andExpect(jsonPath("$.errors[0].message").value("本文を入力するか、画像を選択してください"));
    createPost(aliceId, "あ".repeat(281))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("本文は280文字以内で入力してください"));
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
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
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
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("この操作は実行できません"));
    updatePost(aliceId, 999_999_999L, "書き換え").andExpect(status().isNotFound());
    updatePost(aliceId, postId, "")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("画像のない投稿は本文を空にできません"));
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

  /** タイムラインを1回取り、レスポンスの JSON を返す */
  private tools.jackson.databind.JsonNode timeline(long userId, String path, String cursor)
      throws Exception {
    var request = as(userId, get(path));
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

  @Test
  void タイムラインは新しい順に20件ずつ_続きはnextCursorで取る() throws Exception {
    for (int i = 1; i <= 21; i++) {
      createPostAndGetId(aliceId, "投稿" + i);
    }
    mockMvc
        .perform(as(aliceId, get("/api/timeline")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(20)))
        .andExpect(jsonPath("$.items[0].content").value("投稿21"))
        .andExpect(jsonPath("$.items[19].content").value("投稿2"))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").value(notNullValue()));

    String cursor = timeline(aliceId, "/api/timeline", null).get("nextCursor").asString();
    mockMvc
        .perform(as(aliceId, get("/api/timeline").param("cursor", cursor)))
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].content").value("投稿1"))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
  }

  @Test
  void 続きを読む前に新しい投稿が増えても_重複も抜けもない() throws Exception {
    for (int i = 1; i <= 25; i++) {
      createPostAndGetId(aliceId, "投稿" + i);
    }
    var first = timeline(aliceId, "/api/timeline", null);
    // 1ページ目を見ている間に、新しい投稿が3件増えた（ページ番号の方式だと、2ページ目の先頭に1ページ目の投稿がずれて入ってくる）
    for (int i = 1; i <= 3; i++) {
      createPostAndGetId(aliceId, "途中で増えた投稿" + i);
    }
    var second = timeline(aliceId, "/api/timeline", first.get("nextCursor").asString());

    java.util.List<String> contents = new java.util.ArrayList<>();
    first.get("items").forEach(item -> contents.add(item.get("content").asString()));
    second.get("items").forEach(item -> contents.add(item.get("content").asString()));
    java.util.List<String> expected = new java.util.ArrayList<>();
    for (int i = 25; i >= 1; i--) {
      expected.add("投稿" + i);
    }
    assertThat(contents).containsExactlyElementsOf(expected);
  }

  @Test
  void 全体タイムラインもカーソルで続きを取れる() throws Exception {
    for (int i = 1; i <= 21; i++) {
      createPostAndGetId(i % 2 == 0 ? aliceId : bobId, "全体" + i);
    }
    var first = timeline(carolId, "/api/timeline/all", null);
    assertThat(first.get("items").get(0).get("content").asString()).isEqualTo("全体21");
    assertThat(first.get("hasNext").asBoolean()).isTrue();
    var second = timeline(carolId, "/api/timeline/all", first.get("nextCursor").asString());
    assertThat(second.get("items").get(0).get("content").asString()).isEqualTo("全体1");
  }

  @Test
  void カーソルの形式が違えば400() throws Exception {
    for (String cursor : new String[] {"not-a-cursor", "abc", "MTox"}) {
      mockMvc
          .perform(as(aliceId, get("/api/timeline").param("cursor", cursor)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("cursor"))
          .andExpect(jsonPath("$.errors[0].message").value("カーソルの形式が正しくありません"));
    }
  }

  // ===== A-16・A-17 新しい投稿の件数 =====

  private ResultActions newCount(long userId, String path, Object since) throws Exception {
    return mockMvc.perform(as(userId, get(path)).param("since", String.valueOf(since)));
  }

  @Test
  void 新しい投稿の件数_sinceより後の他人の投稿を数え_自分の投稿は数えない() throws Exception {
    long since = createPostAndGetId(bobId, "見えている一番新しい投稿");
    createPostAndGetId(bobId, "新しい投稿1");
    createPostAndGetId(carolId, "新しい投稿2");
    createPostAndGetId(aliceId, "自分の投稿");

    newCount(aliceId, "/api/timeline/all/new-count", since)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(2));
    // since 自身も「より後」に入らないことの確認。0 にすると DB に残っている動作確認用の投稿まで数えてしまうので、直前の ID にする
    newCount(aliceId, "/api/timeline/all/new-count", since - 1)
        .andExpect(jsonPath("$.count").value(3));
  }

  @Test
  void 新しい投稿の件数_フォロー中はフォロー中の人の投稿だけ数える() throws Exception {
    follow(aliceId, bobId);
    createPostAndGetId(bobId, "フォロー中の人の投稿");
    createPostAndGetId(carolId, "フォローしていない人の投稿");
    createPostAndGetId(aliceId, "自分の投稿");

    newCount(aliceId, "/api/timeline/new-count", 0).andExpect(jsonPath("$.count").value(1));
  }

  @Test
  void 新しい投稿の件数_上限で数えるのをやめる() throws Exception {
    for (int i = 0; i < PostService.NEW_COUNT_LIMIT + 5; i++) {
      jdbcTemplate.update(
          "INSERT INTO posts (user_id, content, created_at, updated_at) VALUES (?, ?, now(), now())",
          bobId,
          "投稿" + i);
    }
    newCount(aliceId, "/api/timeline/all/new-count", 0)
        .andExpect(jsonPath("$.count").value(PostService.NEW_COUNT_LIMIT));
  }

  @Test
  void 新しい投稿の件数_sinceがないか数字でなければ400_未ログインは401() throws Exception {
    mockMvc
        .perform(as(aliceId, get("/api/timeline/new-count")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("since"));
    newCount(aliceId, "/api/timeline/all/new-count", "abc")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("since"));
    mockMvc
        .perform(get("/api/timeline/new-count").param("since", "0"))
        .andExpect(status().isUnauthorized());
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
