package com.okimoto.sns.backend.comment;

import static com.okimoto.sns.backend.support.TestTokens.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.auth.UserResponse;
import com.okimoto.sns.backend.support.ControllerTest;
import com.okimoto.sns.backend.support.TestTokens;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * CommentController（A-30〜A-32）。テスト計画書 5.3 の基本ケース（Service は偽物）。
 *
 * <p>境界値分析：本文は前後の空白・改行を除いて1〜280文字（絵文字も1文字）。空・空白だけ・281文字・本文なしは 400。
 */
@ControllerTest(CommentController.class)
class CommentControllerWebMvcTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");
  private static final String CONTENT_ERROR = "コメントは1〜280文字で入力してください";
  private static final UserResponse AUTHOR = new UserResponse(1, "alice", "アリス", null);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtService jwtService;
  @MockitoBean private CommentService commentService;

  @BeforeEach
  void setUp() {
    TestTokens.teach(jwtService);
  }

  private MockHttpServletRequestBuilder postComment(String content) throws Exception {
    return as(1, post("/api/posts/10/comments"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("content", content)));
  }

  // ===== 一覧 =====

  @Test
  void 一覧は200で_中身と続きのカーソルを返し_カーソルをそのままServiceに渡す() throws Exception {
    when(commentService.list(1, 10, "next-cursor"))
        .thenReturn(
            new CursorPageResponse<>(
                List.of(new CommentResponse(5, "わかりやすいです！", AUTHOR, NOW, true)), "cursor-2", true));

    mockMvc
        .perform(as(1, get("/api/posts/10/comments").param("cursor", "next-cursor")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(5))
        .andExpect(jsonPath("$.items[0].content").value("わかりやすいです！"))
        .andExpect(jsonPath("$.items[0].author.username").value("alice"))
        .andExpect(jsonPath("$.items[0].author.iconUrl").isEmpty())
        .andExpect(jsonPath("$.items[0].createdAt").isString())
        .andExpect(jsonPath("$.items[0].mine").value(true))
        .andExpect(jsonPath("$.nextCursor").value("cursor-2"))
        .andExpect(jsonPath("$.hasNext").value(true));
  }

  @Test
  void カーソルなしで呼ぶと_nullをServiceに渡す() throws Exception {
    when(commentService.list(1, 10, null))
        .thenReturn(new CursorPageResponse<>(List.of(), null, false));

    mockMvc
        .perform(as(1, get("/api/posts/10/comments")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.nextCursor").isEmpty())
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  // ===== 投稿 =====

  @Test
  void 投稿すると201で_投稿後のコメント数も返す() throws Exception {
    when(commentService.create(1, 10, "わかりやすいです！"))
        .thenReturn(new CreatedCommentResponse(5, "わかりやすいです！", AUTHOR, NOW, true, 4));

    mockMvc
        .perform(postComment("わかりやすいです！"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(5))
        .andExpect(jsonPath("$.commentCount").value(4));
  }

  @Test
  void 本文は絵文字280個まで受け付ける() throws Exception {
    mockMvc.perform(postComment("🎉".repeat(280))).andExpect(status().isCreated());

    verify(commentService).create(1, 10, "🎉".repeat(280));
  }

  @Test
  void ブラウザが送る改行のCRLFはLFにそろえてServiceに渡す() throws Exception {
    mockMvc.perform(postComment("1行目\r\n2行目")).andExpect(status().isCreated());

    verify(commentService).create(1, 10, "1行目\n2行目");
  }

  @ParameterizedTest(name = "本文「{0}」は 400")
  @ValueSource(strings = {"", "   ", "\n"})
  void 本文が空や空白だけなら400で_Serviceを呼ばない(String content) throws Exception {
    mockMvc
        .perform(postComment(content))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("content"))
        .andExpect(jsonPath("$.errors[0].message").value(CONTENT_ERROR));
    verify(commentService, never()).create(anyLong(), anyLong(), any());
  }

  @Test
  void 本文が281文字なら400() throws Exception {
    mockMvc
        .perform(postComment("あ".repeat(281)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value(CONTENT_ERROR));
  }

  @Test
  void 本文の項目がなければ400() throws Exception {
    mockMvc
        .perform(
            as(1, post("/api/posts/10/comments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("content"));
  }

  @Test
  void JSONが壊れていれば400_JSON以外の形式なら415() throws Exception {
    mockMvc
        .perform(
            as(1, post("/api/posts/10/comments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    mockMvc
        .perform(
            as(1, post("/api/posts/10/comments")).contentType(MediaType.TEXT_PLAIN).content("本文"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void 投稿がなければ404() throws Exception {
    when(commentService.create(1, 10, "コメント"))
        .thenThrow(new ApiException(ErrorCode.POST_NOT_FOUND));

    mockMvc
        .perform(postComment("コメント"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  // ===== 削除 =====

  @Test
  void 削除すると200で_削除後のコメント数を返す() throws Exception {
    when(commentService.delete(1, 5)).thenReturn(new CommentCountResponse(3));

    mockMvc
        .perform(as(1, delete("/api/comments/5")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commentCount").value(3));
  }

  @Test
  void 他人のコメントは403_ないコメントは404() throws Exception {
    when(commentService.delete(1, 5)).thenThrow(new ApiException(ErrorCode.FORBIDDEN));
    when(commentService.delete(1, 6)).thenThrow(new ApiException(ErrorCode.COMMENT_NOT_FOUND));

    mockMvc
        .perform(as(1, delete("/api/comments/5")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mockMvc
        .perform(as(1, delete("/api/comments/6")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
  }

  // ===== 認証 =====

  @Test
  void ログインなしなら3つともすべて401で_Serviceを呼ばない() throws Exception {
    mockMvc.perform(get("/api/posts/10/comments")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/posts/10/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete("/api/comments/5"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    verifyNoInteractions(commentService);
  }
}
