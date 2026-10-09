package com.okimoto.sns.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.post.Post;
import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.storage.ImageStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.ObjectMapper;

/**
 * プロフィール編集（A-62）と、各 API の iconUrl のテスト。アイコンは LocalStack に実際に保存する（docker compose up -d localstack
 * で起動しておくこと）。
 *
 * <p>テストごとにロールバックするので DB にデータは残らない。保存したアイコンも、ロールバックのときに ProfileEditService が削除する。
 * コミット後に古いアイコンが消えることは ProfileIconReplaceTest で確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class ProfileEditTest {

  /** 1×1 ピクセルの PNG */
  static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

  static final byte[] GIF =
      "GIF89a\u0001\u0000\u0001\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;
  @Autowired private JwtService jwtService;
  @MockitoSpyBean private ImageStorage imageStorage;

  private final HttpClient http = HttpClient.newHttpClient();
  private long aliceId;

  @BeforeEach
  void setUp() {
    User user =
        new User("e_alice", "アリス", "e_alice@example.com", "dummy-hash", OffsetDateTime.now());
    user.setBio("もとの自己紹介");
    userMapper.insert(user);
    aliceId = user.getId();
  }

  private MockMultipartHttpServletRequestBuilder put() {
    MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, "/api/users/me");
    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(aliceId));
    return request;
  }

  private ResultActions update(String displayName, String bio, MockMultipartFile icon)
      throws Exception {
    MockMultipartHttpServletRequestBuilder request = put();
    request.param("displayName", displayName);
    if (bio != null) {
      request.param("bio", bio);
    }
    if (icon != null) {
      request.file(icon);
    }
    return mockMvc.perform(request);
  }

  private static MockMultipartFile icon(String filename, String contentType, byte[] bytes) {
    return new MockMultipartFile("icon", filename, contentType, bytes);
  }

  private int statusOf(String url) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  // ===== 表示名・自己紹介 =====

  @Test
  void 表示名と自己紹介を変更でき_前後の空白は取り除かれ_更新後のプロフィールが返る() throws Exception {
    update("  新しい名前  ", "  新しい自己紹介\n2行目  ", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("e_alice"))
        .andExpect(jsonPath("$.displayName").value("新しい名前"))
        .andExpect(jsonPath("$.bio").value("新しい自己紹介\n2行目"))
        .andExpect(jsonPath("$.iconUrl").isEmpty())
        .andExpect(jsonPath("$.me").value(true));
  }

  @Test
  void ブラウザが送る改行の形式CRLFはLFにそろえ_改行1つを1文字と数える() throws Exception {
    update("アリス", "1行目\r\n2行目", null).andExpect(jsonPath("$.bio").value("1行目\n2行目"));
    // 80文字 + 改行 + 79文字 = 160文字。\r\n を2文字と数えると161文字になって保存できない
    update("アリス", "あ".repeat(80) + "\r\n" + "い".repeat(79), null).andExpect(status().isOk());
  }

  @Test
  void 自己紹介を空にすると消える() throws Exception {
    update("アリス", "   ", null).andExpect(status().isOk()).andExpect(jsonPath("$.bio").isEmpty());
    update("アリス", null, null).andExpect(status().isOk()).andExpect(jsonPath("$.bio").isEmpty());
  }

  @Test
  void 表示名が空や51文字_自己紹介が161文字は400で項目ごとのメッセージ() throws Exception {
    update("  ", "自己紹介", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("displayName"))
        .andExpect(jsonPath("$.errors[0].message").value("表示名は1〜50文字で入力してください"));
    update("あ".repeat(51), null, null).andExpect(status().isBadRequest());
    update("あ".repeat(50), "い".repeat(160), null).andExpect(status().isOk());
    update("アリス", "い".repeat(161), null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("bio"))
        .andExpect(jsonPath("$.errors[0].message").value("自己紹介は160文字以内で入力してください"));
  }

  @Test
  void 未ログインは401() throws Exception {
    mockMvc
        .perform(multipart(HttpMethod.PUT, "/api/users/me").param("displayName", "x"))
        .andExpect(status().isUnauthorized());
  }

  // ===== アイコン =====

  @Test
  void アイコンを保存するとiconUrlで表示でき_いろいろなAPIのiconUrlにも出る() throws Exception {
    String json =
        update("アリス", null, icon("me.png", "image/png", PNG))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String iconUrl = objectMapper.readTree(json).get("iconUrl").asString();
    assertThat(iconUrl).matches("http://localhost:4566/sns-app-images/icons/[0-9a-f-]{36}\\.png");
    assertThat(statusOf(iconUrl)).isEqualTo(200);

    // ログイン中のユーザー（A-03）・投稿者（A-12）・プロフィール（A-60）にも同じ URL が出る
    Post post = new Post(aliceId, "アリスの投稿", OffsetDateTime.now());
    postMapper.insert(post);
    String bearer = "Bearer " + jwtService.issue(aliceId);
    mockMvc
        .perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(jsonPath("$.iconUrl").value(iconUrl));
    mockMvc
        .perform(get("/api/posts/{id}", post.getId()).header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(jsonPath("$.author.iconUrl").value(iconUrl));
    mockMvc
        .perform(get("/api/users/e_alice").header(HttpHeaders.AUTHORIZATION, bearer))
        .andExpect(jsonPath("$.iconUrl").value(iconUrl));
  }

  @Test
  void アイコンを送らなければアイコンは今のまま() throws Exception {
    String json =
        update("アリス", null, icon("me.gif", "image/gif", GIF))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String iconUrl = objectMapper.readTree(json).get("iconUrl").asString();
    assertThat(iconUrl).endsWith(".gif");
    update("名前だけ変更", null, null).andExpect(jsonPath("$.iconUrl").value(iconUrl));
  }

  @Test
  void 形式はファイル名ではなく中身で判定する() throws Exception {
    // 中身が JPEG なら、名前が .png でも jpg として保存する
    update("アリス", null, icon("photo.png", "image/png", JPEG))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.iconUrl").value(org.hamcrest.Matchers.endsWith(".jpg")));
    // 名前と Content-Type が画像でも、中身が画像でなければ 400
    update("アリス", null, icon("evil.png", "image/png", "<script>alert(1)</script>".getBytes()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("icon"))
        .andExpect(jsonPath("$.errors[0].message").value("jpg・png・gif の画像を選択してください"));
  }

  @Test
  void 五MBを超えるアイコンは400で_表示名も更新しない() throws Exception {
    byte[] big = new byte[(int) ProfileEditService.MAX_ICON_BYTES + 1];
    System.arraycopy(PNG, 0, big, 0, PNG.length);
    update("変わらないはず", null, icon("big.png", "image/png", big))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("icon"))
        .andExpect(jsonPath("$.errors[0].message").value("5MB以下の画像を選択してください"));
    assertThat(userMapper.findById(aliceId).orElseThrow().getDisplayName()).isEqualTo("アリス");
  }

  @Test
  void アイコンのアップロードに失敗したら500で_表示名と自己紹介も更新しない() throws Exception {
    doThrow(SdkException.builder().message("S3 につながらない").build())
        .when(imageStorage)
        .upload(any(), any(), any());
    update("変わらないはず", "変わらないはず", icon("me.png", "image/png", PNG))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_FAILED"))
        .andExpect(jsonPath("$.message").value("画像のアップロードに失敗しました。時間をおいてもう一度お試しください"));
    User user = userMapper.findById(aliceId).orElseThrow();
    assertThat(user.getDisplayName()).isEqualTo("アリス");
    assertThat(user.getBio()).isEqualTo("もとの自己紹介");
    assertThat(user.getIconKey()).isNull();
  }
}
