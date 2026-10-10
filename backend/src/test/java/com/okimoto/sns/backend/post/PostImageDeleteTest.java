package com.okimoto.sns.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 投稿を削除したとき、DB のコミット後に S3（LocalStack）の画像が消えることのテスト。
 *
 * <p>画像はコミットされてから消すので、このテストはロールバックせずに実際にコミットする。テストの最後に、作ったユーザーを削除する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostImageDeleteTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final HttpClient http = HttpClient.newHttpClient();
  private long userId;

  @BeforeEach
  void setUp() {
    String username = "d_" + UUID.randomUUID().toString().substring(0, 8);
    User user =
        new User(username, "削除", username + "@example.com", "dummy-hash", OffsetDateTime.now());
    userMapper.insert(user);
    userId = user.getId();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  private int statusOf(String url) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void 投稿を削除すると_コミット後に画像もS3から消える() throws Exception {
    String bearer = "Bearer " + jwtService.issue(userId);
    String json =
        mockMvc
            .perform(
                multipart("/api/posts")
                    .file(PostImageTest.image("a.png", "image/png", PostImageTest.PNG))
                    .file(PostImageTest.image("b.gif", "image/gif", PostImageTest.GIF))
                    .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode post = objectMapper.readTree(json);
    String first = post.get("images").get(0).get("url").asString();
    String second = post.get("images").get(1).get("url").asString();
    assertThat(statusOf(first)).isEqualTo(200);
    assertThat(statusOf(second)).isEqualTo(200);

    mockMvc
        .perform(
            delete("/api/posts/{id}", post.get("id").asLong())
                .header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(status().isNoContent());

    assertThat(statusOf(first)).isEqualTo(404);
    assertThat(statusOf(second)).isEqualTo(404);
    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM post_images WHERE post_id = ?",
            Integer.class,
            post.get("id").asLong());
    assertThat(rows).isZero();
  }
}
