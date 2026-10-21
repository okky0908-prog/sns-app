package com.okimoto.sns.backend.post;

import static com.okimoto.sns.backend.support.TestTokens.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.auth.UserResponse;
import com.okimoto.sns.backend.support.ControllerTest;
import com.okimoto.sns.backend.support.TestTokens;
import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * PostController（A-10〜A-17）。テスト計画書 5.3 の基本ケース（Service は偽物）。
 *
 * <ul>
 *   <li>境界値分析：編集の本文は280文字まで（281文字は 400）。空でもよい（画像のある投稿は空にできるため。画像の有無は Service が判定する）
 *   <li>形の違うリクエスト：since がない・数字でない、作成を JSON で送った（multipart でない）
 * </ul>
 */
@ControllerTest(PostController.class)
class PostControllerWebMvcTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtService jwtService;
  @MockitoBean private PostService postService;

  @BeforeEach
  void setUp() {
    TestTokens.teach(jwtService);
  }

  private static PostResponse postResponse(long id, String content) {
    return new PostResponse(
        id,
        content,
        List.of(new PostResponse.PostImageResponse("https://cdn/a.png", 1)),
        new UserResponse(1, "alice", "アリス", null),
        3,
        true,
        2,
        null,
        NOW,
        true);
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  // ===== タイムライン・新しい投稿の件数 =====

  @Test
  void フォロー中タイムラインは200で_投稿の形とカーソルを返す() throws Exception {
    when(postService.timeline(1, "c1", true))
        .thenReturn(new CursorPageResponse<>(List.of(postResponse(10, "本文")), "c2", true));

    mockMvc
        .perform(as(1, get("/api/timeline").param("cursor", "c1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(10))
        .andExpect(jsonPath("$.items[0].images[0].url").value("https://cdn/a.png"))
        .andExpect(jsonPath("$.items[0].images[0].sortOrder").value(1))
        .andExpect(jsonPath("$.items[0].author.displayName").value("アリス"))
        .andExpect(jsonPath("$.items[0].likeCount").value(3))
        .andExpect(jsonPath("$.items[0].likedByMe").value(true))
        .andExpect(jsonPath("$.items[0].commentCount").value(2))
        .andExpect(jsonPath("$.items[0].editedAt").isEmpty())
        .andExpect(jsonPath("$.items[0].mine").value(true))
        .andExpect(jsonPath("$.nextCursor").value("c2"));
  }

  @Test
  void 全体タイムラインとそのユーザーの投稿一覧も_それぞれのServiceを呼ぶ() throws Exception {
    when(postService.timeline(1, null, false))
        .thenReturn(new CursorPageResponse<>(List.of(), null, false));

    mockMvc.perform(as(1, get("/api/timeline/all"))).andExpect(status().isOk());

    verify(postService).timeline(1, null, false);
  }

  @Test
  void 新しい投稿の件数は200で_フォロー中と全体でServiceの呼び方が違う() throws Exception {
    when(postService.countNewPosts(1, 30, true)).thenReturn(new NewPostCountResponse(3));
    when(postService.countNewPosts(1, 30, false)).thenReturn(new NewPostCountResponse(100));

    mockMvc
        .perform(as(1, get("/api/timeline/new-count").param("since", "30")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(3));
    mockMvc
        .perform(as(1, get("/api/timeline/all/new-count").param("since", "30")))
        .andExpect(jsonPath("$.count").value(100));
  }

  @Test
  void sinceがなければ400で_項目にsinceが入る() throws Exception {
    mockMvc
        .perform(as(1, get("/api/timeline/new-count")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("since"))
        .andExpect(jsonPath("$.errors[0].message").value("指定してください"));
  }

  @Test
  void sinceが数字でなければ400() throws Exception {
    mockMvc
        .perform(as(1, get("/api/timeline/all/new-count").param("since", "abc")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("since"));
  }

  // ===== 作成（multipart） =====

  @Test
  void 作成は本文と画像を受け取ってServiceに渡し_201で作成した投稿を返す() throws Exception {
    when(postService.create(eq(1L), eq("本文"), any())).thenReturn(postResponse(10, "本文"));
    MockMultipartFile a = new MockMultipartFile("images", "a.png", "image/png", new byte[] {1});
    MockMultipartFile b = new MockMultipartFile("images", "b.gif", "image/gif", new byte[] {2});

    mockMvc
        .perform(as(1, multipart("/api/posts").file(a).file(b).param("content", "本文")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(10));

    // 画像は選んだ順のまま渡す
    verify(postService)
        .create(
            eq(1L),
            eq("本文"),
            argThat(
                files ->
                    files.size() == 2
                        && files.get(0).getOriginalFilename().equals("a.png")
                        && files.get(1).getOriginalFilename().equals("b.gif")));
  }

  @Test
  void 本文も画像もなければ_nullのままServiceに渡し_Serviceの入力エラーを400で返す() throws Exception {
    when(postService.create(eq(1L), isNull(), isNull()))
        .thenThrow(
            ApiException.badRequest(
                List.of(new ApiError.FieldError("content", "本文を入力するか、画像を選択してください"))));

    mockMvc
        .perform(as(1, multipart("/api/posts")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("content"))
        .andExpect(jsonPath("$.errors[0].message").value("本文を入力するか、画像を選択してください"));
  }

  @Test
  void 作成をJSONで送ったら415() throws Exception {
    mockMvc
        .perform(
            as(1, post("/api/posts"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "本文"))))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    verify(postService, never()).create(anyLong(), any(), any());
  }

  // ===== 詳細・編集・削除 =====

  @Test
  void 詳細は200_ない投稿は404() throws Exception {
    when(postService.get(1, 10)).thenReturn(postResponse(10, "本文"));
    when(postService.get(1, 11)).thenThrow(new ApiException(ErrorCode.POST_NOT_FOUND));

    mockMvc.perform(as(1, get("/api/posts/10"))).andExpect(status().isOk());
    mockMvc
        .perform(as(1, get("/api/posts/11")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  @Test
  void 編集は280文字まで受け付け_改行のCRLFはLFにそろえて渡す() throws Exception {
    when(postService.update(eq(1L), eq(10L), any())).thenReturn(postResponse(10, "x"));

    mockMvc
        .perform(
            as(1, put("/api/posts/10"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "🎉".repeat(280)))))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            as(1, put("/api/posts/10"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "a\r\nb"))))
        .andExpect(status().isOk());

    verify(postService).update(1, 10, "🎉".repeat(280));
    verify(postService).update(1, 10, "a\nb");
  }

  @Test
  void 編集の本文が281文字なら400で_Serviceを呼ばない() throws Exception {
    mockMvc
        .perform(
            as(1, put("/api/posts/10"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "あ".repeat(281)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("content"))
        .andExpect(jsonPath("$.errors[0].message").value("本文は280文字以内で入力してください"));
    verify(postService, never()).update(anyLong(), anyLong(), any());
  }

  @Test
  void 編集の本文が空でも受け付けて_画像の有無の判定はServiceに任せる() throws Exception {
    mockMvc
        .perform(as(1, put("/api/posts/10")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());

    verify(postService).update(1, 10, null);
  }

  @Test
  void 他人の投稿の編集は403() throws Exception {
    when(postService.update(1, 10, "本文")).thenThrow(new ApiException(ErrorCode.FORBIDDEN));

    mockMvc
        .perform(
            as(1, put("/api/posts/10"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "本文"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void 削除は204で本文は空() throws Exception {
    mockMvc
        .perform(as(1, delete("/api/posts/10")))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(postService).delete(1, 10);
  }

  @Test
  void ログインなしなら8つともすべて401で_Serviceを呼ばない() throws Exception {
    mockMvc.perform(get("/api/timeline")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/timeline/all")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/timeline/new-count").param("since", "1"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/timeline/all/new-count").param("since", "1"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(multipart("/api/posts").param("content", "x"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/posts/10")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(put("/api/posts/10").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/posts/10"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    verifyNoInteractions(postService);
  }
}
