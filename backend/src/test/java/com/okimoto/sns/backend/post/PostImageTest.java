package com.okimoto.sns.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.storage.UploadedImage;
import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 画像付きの投稿（A-11 の images）と、一覧・詳細の images のテスト。画像は LocalStack に実際に保存する（docker compose up -d
 * localstack で起動しておくこと）。
 *
 * <p>テストごとにロールバックするので DB にデータは残らない。保存した画像も、ロールバックのときに PostService が削除する。 コミット後に削除した投稿の画像が消えることは
 * PostImageDeleteTest で確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class PostImageTest {

  /** 1×1 ピクセルの PNG */
  static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

  static final byte[] GIF = "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private JwtService jwtService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoSpyBean private ImageStorage imageStorage;

  private final HttpClient http = HttpClient.newHttpClient();
  private long aliceId;

  @BeforeEach
  void setUp() {
    User user =
        new User("i_alice", "アリス", "i_alice@example.com", "dummy-hash", OffsetDateTime.now());
    userMapper.insert(user);
    aliceId = user.getId();
  }

  static MockMultipartFile image(String name, String contentType, byte[] bytes) {
    return new MockMultipartFile("images", name, contentType, bytes);
  }

  private ResultActions create(String content, MockMultipartFile... images) throws Exception {
    MockMultipartHttpServletRequestBuilder request = multipart("/api/posts");
    if (content != null) {
      request.param("content", content);
    }
    for (MockMultipartFile file : images) {
      request.file(file);
    }
    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(aliceId));
    return mockMvc.perform(request);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private int statusOf(String url) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  private int postRows() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM posts WHERE user_id = ?", Integer.class, aliceId);
  }

  // ===== 作成 =====

  @Test
  void 本文と画像で投稿でき_画像は選んだ順に表示用のURLで返り_表示できる() throws Exception {
    JsonNode post =
        body(
            create("画像つき", image("a.png", "image/png", PNG), image("b.gif", "image/gif", GIF))
                .andExpect(status().isCreated()));
    assertThat(post.get("content").asString()).isEqualTo("画像つき");
    JsonNode images = post.get("images");
    assertThat(images).hasSize(2);
    OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    String folder =
        String.format(
            "http://localhost:4566/sns-app-images/posts/%04d/%02d/",
            now.getYear(), now.getMonthValue());
    assertThat(images.get(0).get("url").asString()).startsWith(folder).endsWith(".png");
    assertThat(images.get(0).get("sortOrder").asInt()).isEqualTo(1);
    assertThat(images.get(1).get("url").asString()).endsWith(".gif");
    assertThat(images.get(1).get("sortOrder").asInt()).isEqualTo(2);
    for (JsonNode image : images) {
      assertThat(statusOf(image.get("url").asString())).isEqualTo(200);
    }
  }

  @Test
  void 画像だけでも投稿できる() throws Exception {
    create(null, image("a.png", "image/png", PNG))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.content").value(""))
        .andExpect(jsonPath("$.images.length()").value(1));
  }

  @Test
  void 本文の改行はブラウザが送るCRLFをLFにそろえる() throws Exception {
    create("1行目\r\n2行目").andExpect(jsonPath("$.content").value("1行目\n2行目"));
  }

  @Test
  void 画像が5枚以上なら400で_何も保存しない() throws Exception {
    MockMultipartFile[] five = new MockMultipartFile[5];
    for (int i = 0; i < 5; i++) {
      five[i] = image(i + ".png", "image/png", PNG);
    }
    create("多すぎる", five)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("images"))
        .andExpect(jsonPath("$.errors[0].message").value("画像は4枚まで添付できます"));
    assertThat(postRows()).isZero();
    org.mockito.Mockito.verify(imageStorage, org.mockito.Mockito.never())
        .upload(any(), any(), any());
  }

  @Test
  void 画像の形式は中身で判定し_5MBを超える画像は400() throws Exception {
    create("偽物", image("evil.png", "image/png", "<script>".getBytes()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("images"))
        .andExpect(jsonPath("$.errors[0].message").value(UploadedImage.TYPE_ERROR));
    byte[] big = new byte[(int) UploadedImage.MAX_BYTES + 1];
    System.arraycopy(PNG, 0, big, 0, PNG.length);
    create("大きすぎる", image("big.png", "image/png", big))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value(UploadedImage.SIZE_ERROR));
    assertThat(postRows()).isZero();
  }

  @Test
  void 画像の保存に失敗したら500で投稿もせず_保存済みの画像は削除する() throws Exception {
    // 1枚目は保存でき、2枚目で失敗する
    List<String> uploadedKeys = new ArrayList<>();
    doCallRealMethod().when(imageStorage).deleteQuietly(any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (!uploadedKeys.isEmpty()) {
                throw SdkException.builder().message("S3 につながらない").build();
              }
              uploadedKeys.add(invocation.getArgument(0));
              return invocation.callRealMethod();
            })
        .when(imageStorage)
        .upload(any(), any(), any());

    create("失敗", image("a.png", "image/png", PNG), image("b.png", "image/png", PNG))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_FAILED"));
    assertThat(postRows()).isZero();
    assertThat(uploadedKeys).hasSize(1);
    assertThat(statusOf("http://localhost:4566/sns-app-images/" + uploadedKeys.getFirst()))
        .isEqualTo(404);
  }

  // ===== 一覧・詳細・編集 =====

  @Test
  void 詳細とタイムラインに画像が出て_画像のない投稿は空() throws Exception {
    long withImage = body(create("画像あり", image("a.png", "image/png", PNG))).get("id").asLong();
    long withoutImage = body(create("画像なし")).get("id").asLong();
    String bearer = "Bearer " + jwtService.issue(aliceId);

    mockMvc
        .perform(get("/api/posts/{id}", withImage).header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(jsonPath("$.images.length()").value(1));
    JsonNode timeline =
        body(mockMvc.perform(get("/api/timeline").header(HttpHeaders.AUTHORIZATION, bearer)));
    assertThat(timeline.get("items").get(0).get("id").asLong()).isEqualTo(withoutImage);
    assertThat(timeline.get("items").get(0).get("images")).isEmpty();
    assertThat(timeline.get("items").get(1).get("id").asLong()).isEqualTo(withImage);
    assertThat(timeline.get("items").get(1).get("images")).hasSize(1);
  }

  @Test
  void 画像のある投稿は本文を空に編集できる() throws Exception {
    long postId = body(create("消す本文", image("a.png", "image/png", PNG))).get("id").asLong();
    mockMvc
        .perform(
            put("/api/posts/{id}", postId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"  \"}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(aliceId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value(""))
        .andExpect(jsonPath("$.images.length()").value(1));
  }
}
