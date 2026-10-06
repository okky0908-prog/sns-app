package com.okimoto.sns.backend.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * タイムラインの通知（A-16、SSE）のテスト。
 *
 * <p>SSE は接続を開いたまま通知を受け取るので、実際にサーバーを起動して HTTP でつなぐ。データはテストの最後に削除する（MockMvc のテストと違い、 ロールバックできないため）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TimelineStreamTest {

  @LocalServerPort private int port;
  @Autowired private UserMapper userMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private final HttpClient http = HttpClient.newHttpClient();
  private final List<InputStream> openStreams = new ArrayList<>();
  private long aliceId;
  private long bobId;
  private long carolId;

  @BeforeEach
  void setUp() {
    aliceId = createUser("st_alice");
    bobId = createUser("st_bob");
    carolId = createUser("st_carol");
    // ボブはアリスをフォローしている。キャロルはフォローしていない
    jdbcTemplate.update(
        "INSERT INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, now())",
        bobId,
        aliceId);
  }

  @AfterEach
  void tearDown() throws Exception {
    for (InputStream stream : openStreams) {
      stream.close();
    }
    jdbcTemplate.update("DELETE FROM users WHERE username IN ('st_alice', 'st_bob', 'st_carol')");
  }

  // ===== 道具 =====

  private long createUser(String username) {
    User user =
        new User(
            username, username + "の名前", username + "@example.com", "dummy", OffsetDateTime.now());
    userMapper.insert(user);
    return user.getId();
  }

  private String bearer(long userId) {
    return "Bearer " + jwtService.issue(userId);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  /** 受け取った通知（イベント名と中身） */
  record Received(String name, JsonNode data) {}

  /** 通知の接続を開き、届いたイベントをキューに入れていく */
  private BlockingQueue<Received> connect(long userId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri("/api/timeline/stream"))
            .header("Authorization", bearer(userId))
            .header("Accept", "text/event-stream")
            .build();
    HttpResponse<InputStream> response =
        http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("text/event-stream");
    openStreams.add(response.body());

    BlockingQueue<Received> queue = new LinkedBlockingQueue<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String name = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                  if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                  } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                  } else if (line.isEmpty() && name != null) {
                    queue.add(new Received(name, objectMapper.readTree(data.toString())));
                    name = null;
                    data.setLength(0);
                  }
                }
              } catch (Exception e) {
                // テストの終わりに接続を閉じたとき
              }
            });
    Received connected = queue.poll(5, TimeUnit.SECONDS);
    assertThat(connected).isNotNull();
    assertThat(connected.name()).isEqualTo("connected");
    return queue;
  }

  private HttpResponse<String> call(String method, String path, long userId, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", bearer(userId))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Received next(BlockingQueue<Received> queue) throws InterruptedException {
    Received received = queue.poll(5, TimeUnit.SECONDS);
    assertThat(received).as("5秒以内に通知が届く").isNotNull();
    return received;
  }

  // ===== テスト =====

  @Test
  void 投稿が作られると接続中の全員に通知され_フォロー中タブに出すかは人ごとに決まる() throws Exception {
    BlockingQueue<Received> alice = connect(aliceId);
    BlockingQueue<Received> bob = connect(bobId);
    BlockingQueue<Received> carol = connect(carolId);

    HttpResponse<String> created =
        call("POST", "/api/posts", aliceId, "{\"content\":\"リアルタイムの投稿\"}");
    assertThat(created.statusCode()).isEqualTo(201);

    Received toAlice = next(alice);
    assertThat(toAlice.name()).isEqualTo("post-created");
    assertThat(toAlice.data().get("post").get("content").asString()).isEqualTo("リアルタイムの投稿");
    assertThat(toAlice.data().get("post").get("mine").asBoolean()).isTrue();
    assertThat(toAlice.data().get("inFollowing").asBoolean()).isTrue(); // 自分の投稿

    Received toBob = next(bob);
    assertThat(toBob.data().get("post").get("mine").asBoolean()).isFalse();
    assertThat(toBob.data().get("inFollowing").asBoolean()).isTrue(); // フォロー中の人の投稿

    Received toCarol = next(carol);
    assertThat(toCarol.data().get("inFollowing").asBoolean()).isFalse(); // 全体タブにだけ出す
  }

  @Test
  void 編集と削除も通知される() throws Exception {
    HttpResponse<String> created = call("POST", "/api/posts", aliceId, "{\"content\":\"編集前\"}");
    long postId = objectMapper.readTree(created.body()).get("id").asLong();
    BlockingQueue<Received> bob = connect(bobId);

    call("PUT", "/api/posts/" + postId, aliceId, "{\"content\":\"編集後\"}");
    Received updated = next(bob);
    assertThat(updated.name()).isEqualTo("post-updated");
    assertThat(updated.data().get("post").get("content").asString()).isEqualTo("編集後");
    assertThat(updated.data().get("post").get("editedAt").isNull()).isFalse();

    call("DELETE", "/api/posts/" + postId, aliceId, null);
    Received deleted = next(bob);
    assertThat(deleted.name()).isEqualTo("post-deleted");
    assertThat(deleted.data().get("postId").asLong()).isEqualTo(postId);
  }

  @Test
  void 失敗した操作は通知しない() throws Exception {
    HttpResponse<String> created = call("POST", "/api/posts", aliceId, "{\"content\":\"アリスの投稿\"}");
    long postId = objectMapper.readTree(created.body()).get("id").asLong();
    BlockingQueue<Received> carol = connect(carolId);

    // 他人の投稿の編集・削除（403）と、空の投稿（400）
    assertThat(call("PUT", "/api/posts/" + postId, bobId, "{\"content\":\"乗っ取り\"}").statusCode())
        .isEqualTo(403);
    assertThat(call("DELETE", "/api/posts/" + postId, bobId, null).statusCode()).isEqualTo(403);
    assertThat(call("POST", "/api/posts", bobId, "{\"content\":\"\"}").statusCode()).isEqualTo(400);
    assertThat(carol.poll(1, TimeUnit.SECONDS)).isNull();
  }

  @Test
  void ログインしていないと接続できない() throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(uri("/api/timeline/stream"))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }
}
