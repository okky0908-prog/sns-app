package com.okimoto.sns.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.storage.ImageStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * アイコンを差し替えたとき、DB のコミット後に古いアイコンが S3（LocalStack）から消えることのテスト。
 *
 * <p>古いアイコンはコミットされてから消すので、このテストはロールバックせずに実際にコミットする。テストの最後に、作ったユーザー（と残ったアイコン）を削除する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileIconReplaceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ImageStorage imageStorage;

  private final HttpClient http = HttpClient.newHttpClient();
  private long userId;

  @BeforeEach
  void setUp() {
    String username = "r_" + UUID.randomUUID().toString().substring(0, 8);
    User user =
        new User(username, "差し替え", username + "@example.com", "dummy-hash", OffsetDateTime.now());
    userMapper.insert(user);
    userId = user.getId();
  }

  @AfterEach
  void tearDown() {
    String iconKey = userMapper.findById(userId).map(User::getIconKey).orElse(null);
    if (iconKey != null) {
      imageStorage.deleteQuietly(iconKey);
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  private String uploadIcon() throws Exception {
    String json =
        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "/api/users/me")
                    .file(new MockMultipartFile("icon", "me.png", "image/png", ProfileEditTest.PNG))
                    .param("displayName", "差し替え")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("iconUrl").asString();
  }

  private int statusOf(String url) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void アイコンを差し替えると_コミット後に古いアイコンが消え_新しいアイコンが表示できる() throws Exception {
    String first = uploadIcon();
    assertThat(statusOf(first)).isEqualTo(200);

    String second = uploadIcon();
    assertThat(second).isNotEqualTo(first);
    assertThat(statusOf(second)).isEqualTo(200);
    assertThat(statusOf(first)).isEqualTo(404);
  }

  @Test
  void 二つの編集が同時に来ても_先の編集のアイコンはS3に残らない() throws Exception {
    // 2つの編集をほぼ同時に送る。ユーザーの行をロックしているので、あとの編集は先の編集のコミットを待ち、
    // 先の編集が保存したアイコンを「古いアイコン」として消す（ロックがないと、両方が古いアイコンなしと読み、片方のアイコンが残り続ける）
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Future<String> first = pool.submit(this::uploadIcon);
      Thread.sleep(50);
      java.util.concurrent.Future<String> second = pool.submit(this::uploadIcon);
      String a = first.get();
      String b = second.get();

      // どちらが先に処理されても、保存されている（あとにコミットされた）アイコンだけが残り、もう一方は消えている
      String saved = userMapper.findById(userId).orElseThrow().getIconKey();
      String kept = a.endsWith(saved) ? a : b;
      String removed = kept.equals(a) ? b : a;
      assertThat(kept).endsWith(saved);
      assertThat(statusOf(kept)).isEqualTo(200);
      assertThat(statusOf(removed)).isEqualTo(404);
    } finally {
      pool.shutdown();
    }
  }
}
