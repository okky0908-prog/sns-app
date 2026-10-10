package com.okimoto.sns.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ユーザー検索（A-70）のテスト。テストごとにロールバックするので、DB にデータは残らない。
 *
 * <p>DB に動作確認用のユーザーが残っていても結果を正確に比べられるよう、ほかと重ならない「zqx」を名前に入れる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class UserSearchTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long viewerId;

  @BeforeEach
  void setUp() {
    viewerId = createUser("zqxviewer", "見ている人");
  }

  private long createUser(String username, String displayName) {
    return createUser(username, displayName, OffsetDateTime.now());
  }

  private long createUser(String username, String displayName, OffsetDateTime createdAt) {
    User user =
        new User(
            username,
            displayName,
            username.toLowerCase(Locale.ROOT) + "@example.com",
            "dummy-hash",
            createdAt);
    userMapper.insert(user);
    return user.getId();
  }

  private ResultActions search(String q) throws Exception {
    return search(q, null);
  }

  private ResultActions search(String q, String cursor) throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/users/search")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(viewerId));
    if (q != null) {
      request.param("q", q);
    }
    if (cursor != null) {
      request.param("cursor", cursor);
    }
    return mockMvc.perform(request);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return objectMapper.readTree(
        result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private List<String> usernames(JsonNode body) {
    List<String> names = new ArrayList<>();
    body.get("items").forEach(item -> names.add(item.get("username").asString()));
    return names;
  }

  // ===== 並び順・一致のしかた =====

  @Test
  void ユーザー名の完全一致_前方一致_それ以外の順で_同じ順位はユーザー名の昇順() throws Exception {
    createUser("b_zqxyam", "途中に含む");
    createUser("zqxyamada", "前方一致");
    createUser("p_other", "表示名にzqxyamを含む");
    createUser("zqxyam", "完全一致");
    createUser("a_zqxyam", "途中に含む");
    createUser("zqxnope", "含まない");

    assertThat(usernames(body(search("zqxyam"))))
        .containsExactly("zqxyam", "zqxyamada", "a_zqxyam", "b_zqxyam", "p_other");
  }

  @Test
  void 大文字小文字を区別せず_先頭のアットマークと前後の空白は取り除く() throws Exception {
    createUser("ZqxYamada", "山田");
    List<String> expected = List.of("ZqxYamada");
    assertThat(usernames(body(search("zqxyamada")))).isEqualTo(expected);
    assertThat(usernames(body(search("ZQXYAMA")))).isEqualTo(expected);
    assertThat(usernames(body(search("  @zqxyama  ")))).isEqualTo(expected);
  }

  @Test
  void 表示名の一部でも検索できる() throws Exception {
    createUser("zqxtaro", "山田zqx太郎");
    assertThat(usernames(body(search("zqx太郎")))).containsExactly("zqxtaro");
  }

  @Test
  void アンダースコアとパーセントは普通の文字として探す() throws Exception {
    createUser("zqx_a1", "アンダースコアあり");
    createUser("zqxba1", "アンダースコアなし"); // エスケープしないと「_」が任意の1文字としてヒットしてしまう
    createUser("zqxper1", "zqx100%達成");
    createUser("zqxper2", "zqx1000点");

    assertThat(usernames(body(search("zqx_a")))).containsExactly("zqx_a1");
    assertThat(usernames(body(search("zqx100%")))).containsExactly("zqxper1");
  }

  @Test
  void 一致するユーザーがいなければ200で空() throws Exception {
    search("zqx存在しない名前")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  // ===== 最近参加したユーザー =====

  @Test
  void キーワードが空なら最近参加したユーザーが新しい順() throws Exception {
    // 動作確認用のユーザーより新しくなるよう、未来の日時で作る
    OffsetDateTime future = OffsetDateTime.now().plusDays(1);
    createUser("zqxold", "古い", future);
    createUser("zqxnew", "新しい", future.plusMinutes(1));

    for (String q : new String[] {null, "", "  ", "@"}) {
      assertThat(usernames(body(search(q))).subList(0, 2)).containsExactly("zqxnew", "zqxold");
    }
  }

  // ===== 続きの読み込み =====

  @Test
  void 検索結果は二十一件以上をカーソルで続きを取れ_重複も抜けもない() throws Exception {
    List<String> expected = new ArrayList<>();
    for (int i = 1; i <= 25; i++) {
      String name = String.format("zqxpage%02d", i);
      createUser(name, "ページ" + i);
      expected.add(name);
    }
    JsonNode first = body(search("zqxpage"));
    assertThat(first.get("items")).hasSize(20);
    assertThat(first.get("hasNext").asBoolean()).isTrue();
    JsonNode second = body(search("zqxpage", first.get("nextCursor").asString()));
    assertThat(second.get("hasNext").asBoolean()).isFalse();

    List<String> all = new ArrayList<>(usernames(first));
    all.addAll(usernames(second));
    assertThat(all).containsExactlyElementsOf(expected);
  }

  @Test
  void 最近参加したユーザーもカーソルで続きを取れる() throws Exception {
    OffsetDateTime future = OffsetDateTime.now().plusDays(1);
    for (int i = 1; i <= 21; i++) {
      createUser(String.format("zqxrecent%02d", i), "新人" + i, future.plusSeconds(i));
    }
    JsonNode first = body(search(""));
    assertThat(usernames(first).getFirst()).isEqualTo("zqxrecent21");
    JsonNode second = body(search("", first.get("nextCursor").asString()));
    // 1ページ目の最後（zqxrecent02）の次は zqxrecent01
    assertThat(usernames(second).getFirst()).isEqualTo("zqxrecent01");
  }

  // ===== 各行の状態・入力チェック =====

  @Test
  void 各行に自分がフォロー中かと自分自身かが入る() throws Exception {
    long followed = createUser("zqxfollowed", "フォロー中");
    createUser("zqxstranger", "フォローしていない");
    jdbcTemplate.update(
        "INSERT INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, now())",
        viewerId,
        followed);

    search("zqxfollowed")
        .andExpect(jsonPath("$.items[0].followedByMe").value(true))
        .andExpect(jsonPath("$.items[0].me").value(false));
    search("zqxstranger").andExpect(jsonPath("$.items[0].followedByMe").value(false));
    search("zqxviewer").andExpect(jsonPath("$.items[0].me").value(true));
  }

  @Test
  void キーワードは50文字まで_51文字は400() throws Exception {
    search("あ".repeat(50)).andExpect(status().isOk());
    search("あ".repeat(51))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("q"))
        .andExpect(jsonPath("$.errors[0].message").value("検索キーワードは50文字以内で入力してください"));
  }

  @Test
  void 形式の違うカーソルは400() throws Exception {
    search("zqx", "zzz").andExpect(status().isBadRequest());
    search("", "zzz").andExpect(status().isBadRequest());
  }

  @Test
  void 未ログインは401() throws Exception {
    mockMvc.perform(get("/api/users/search").param("q", "a")).andExpect(status().isUnauthorized());
  }
}
