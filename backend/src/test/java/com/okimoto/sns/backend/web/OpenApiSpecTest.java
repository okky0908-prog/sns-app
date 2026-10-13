package com.okimoto.sns.backend.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 仕様書（OpenAPI）のテスト。
 *
 * <p>springdoc が作る仕様書を docs/openapi.yaml に書き出す。CI はこのあと docs/openapi.yaml に差分がないかを確かめるので、API
 * を変えたのに仕様書をコミットし忘れると CI が落ちる（docs/api.md「API 仕様書（OpenAPI）」）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecTest {

  /** テストは backend/ で動くので、リポジトリ直下の docs/ は1つ上 */
  private static final Path SPEC_FILE = Path.of("../docs/openapi.yaml");

  @Autowired private MockMvc mockMvc;

  @Test
  void API仕様書をdocsに書き出す() throws Exception {
    String yaml =
        mockMvc
            .perform(get("/v3/api-docs.yaml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    Files.writeString(SPEC_FILE, yaml, StandardCharsets.UTF_8);
  }

  @Test
  void ログインなしでSwagger_UIを開ける() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void ログインなしで呼べるAPIだけが認証なしになる() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.security[0]", hasKey("bearerAuth")))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        // 新規登録・ログイン・再発行・ログアウトは認証なし（SecurityConfig と同じ）
        .andExpect(jsonPath("$.paths['/api/auth/signup'].post.security", empty()))
        .andExpect(jsonPath("$.paths['/api/auth/login'].post.security", empty()))
        .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.security", empty()))
        .andExpect(jsonPath("$.paths['/api/auth/logout'].post.security", empty()))
        .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses", not(hasKey("401"))))
        // それ以外は全体の既定（bearerAuth）のままで、401 のレスポンスがある
        .andExpect(jsonPath("$.paths['/api/auth/me'].get", not(hasKey("security"))))
        .andExpect(jsonPath("$.paths['/api/timeline'].get.responses", hasKey("401")));
  }

  @Test
  void エラーの形式とcodeの一覧を載せる() throws Exception {
    String[] codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::name).toArray(String[]::new);
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(jsonPath("$.components.schemas.ApiError.properties.code.enum", contains(codes)))
        .andExpect(
            jsonPath(
                    "$.paths['/api/posts/{postId}'].get.responses.default.content['application/json'].schema['$ref']")
                .value("#/components/schemas/ApiError"));
  }

  @Test
  void 分類はdocsと同じ順に並べ概要はJavadocの最初の文にする() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(
            jsonPath(
                "$.tags[*].name",
                contains("認証", "投稿・タイムライン", "コメント", "いいね", "フォロー", "プロフィール・ユーザー検索")))
        .andExpect(jsonPath("$.paths['/api/posts/{postId}'].get.summary").value("A-12 投稿詳細"))
        // 括弧の中の「。」では区切らない
        .andExpect(
            jsonPath("$.paths['/api/posts/{postId}'].put.summary").value("A-13 投稿編集（本文のみ。本人だけ）"));
  }

  @Test
  void ステータスと文字数の決まりを載せる() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        // ResponseEntity で返す API のステータス
        .andExpect(jsonPath("$.paths['/api/auth/signup'].post.responses", hasKey("201")))
        .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses", hasKey("204")))
        // TextLength（JSON の項目）
        .andExpect(
            jsonPath("$.components.schemas.PostRequest.properties.content.maxLength").value(280))
        .andExpect(
            jsonPath("$.components.schemas.CommentRequest.properties.content.minLength").value(1))
        // 1文字以上の TextLength は必須（新規登録の displayName）。上限だけなら必須にしない（投稿編集の content）
        .andExpect(jsonPath("$.components.schemas.SignupRequest.required", hasItem("displayName")))
        .andExpect(jsonPath("$.components.schemas.PostRequest", not(hasKey("required"))));
  }

  /** フロントの型（frontend/src/api/schema.ts）を正しく作れるよう、レスポンスの項目の「必ず入るか」「null になりうるか」を載せる */
  @Test
  void レスポンスの項目は必須でnullになりうる項目だけnullを許す() throws Exception {
    String post = "$.components.schemas.PostResponse";
    mockMvc
        .perform(get("/v3/api-docs"))
        // 入れ子のスキーマ（author）・一覧の中身・エラーも含めて、レスポンスの項目はすべて必須
        .andExpect(
            jsonPath(
                post + ".required",
                containsInAnyOrder(
                    "id",
                    "content",
                    "images",
                    "author",
                    "likeCount",
                    "likedByMe",
                    "commentCount",
                    "editedAt",
                    "createdAt",
                    "mine")))
        .andExpect(jsonPath("$.components.schemas.UserResponse.required", hasItem("iconUrl")))
        .andExpect(jsonPath("$.components.schemas.UserListItemResponse.required", hasItem("bio")))
        .andExpect(jsonPath("$.components.schemas.ApiError.required", hasItem("errors")))
        // null になりうる項目（@Schema(nullable = true)）だけ、型に null が入る
        .andExpect(jsonPath(post + ".properties.editedAt.type", contains("string", "null")))
        .andExpect(jsonPath(post + ".properties.createdAt.type").value("string"))
        .andExpect(
            jsonPath(
                "$.components.schemas.CursorPageResponsePostResponse.properties.nextCursor.type",
                contains("string", "null")))
        .andExpect(
            jsonPath(
                "$.components.schemas.ProfileResponse.properties.bio.type",
                contains("string", "null")));
  }

  @Test
  void multipartのAPIはテキストもファイルもフォームの項目として載せる() throws Exception {
    String postForm =
        "$.paths['/api/posts'].post.requestBody.content['multipart/form-data'].schema";
    String profileForm =
        "$.paths['/api/users/me'].put.requestBody.content['multipart/form-data'].schema";
    mockMvc
        .perform(get("/v3/api-docs"))
        // 投稿作成：本文はクエリではなくフォームのパート
        .andExpect(jsonPath(postForm + ".properties", hasKey("content")))
        .andExpect(jsonPath(postForm + ".properties", hasKey("images")))
        .andExpect(jsonPath("$.paths['/api/posts'].post", not(hasKey("parameters"))))
        // プロフィール編集：テキストの項目（TextLength 付き）とアイコン画像
        .andExpect(jsonPath(profileForm + ".properties.displayName.maxLength").value(50))
        .andExpect(jsonPath(profileForm + ".properties.bio.maxLength").value(160))
        .andExpect(jsonPath(profileForm + ".properties.icon.format").value("binary"))
        .andExpect(jsonPath(profileForm + ".required", hasItem("displayName")));
  }

  /** 本番の設定（SPRINGDOC_ENABLED=false）では、仕様書も Swagger UI も開けない */
  @Nested
  @SpringBootTest(
      properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  class 本番の設定 {

    @Autowired private MockMvc mockMvc;

    @Test
    void 仕様書もSwagger_UIも404になる() throws Exception {
      mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
      mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
  }
}
