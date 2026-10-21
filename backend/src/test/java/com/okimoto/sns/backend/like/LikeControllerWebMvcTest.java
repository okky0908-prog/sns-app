package com.okimoto.sns.backend.like;

import static com.okimoto.sns.backend.support.TestTokens.as;
import static com.okimoto.sns.backend.support.TestTokens.withInvalidToken;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.support.ControllerTest;
import com.okimoto.sns.backend.support.TestTokens;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** LikeController（A-40・A-41）。テスト計画書 5.3 の基本ケース（Service は偽物）。 */
@ControllerTest(LikeController.class)
class LikeControllerWebMvcTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @MockitoBean private LikeService likeService;

  @BeforeEach
  void setUp() {
    TestTokens.teach(jwtService);
  }

  @Test
  void いいねすると200で_いいね数といいね済みかを返す() throws Exception {
    when(likeService.like(1, 10)).thenReturn(new LikeResponse(3, true));

    mockMvc
        .perform(as(1, post("/api/posts/10/likes")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(3))
        .andExpect(jsonPath("$.likedByMe").value(true));
  }

  @Test
  void 取り消すと200で_いいね数といいね済みかを返す() throws Exception {
    when(likeService.unlike(1, 10)).thenReturn(new LikeResponse(2, false));

    mockMvc
        .perform(as(1, delete("/api/posts/10/likes")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(2))
        .andExpect(jsonPath("$.likedByMe").value(false));
  }

  @Test
  void ログインなし_無効なトークンは401で_Serviceを呼ばない() throws Exception {
    mockMvc
        .perform(post("/api/posts/10/likes"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mockMvc
        .perform(withInvalidToken(delete("/api/posts/10/likes")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    verify(likeService, never()).like(anyLong(), anyLong());
    verify(likeService, never()).unlike(anyLong(), anyLong());
  }

  @Test
  void 投稿がなければ404() throws Exception {
    when(likeService.like(1, 10)).thenThrow(new ApiException(ErrorCode.POST_NOT_FOUND));

    mockMvc
        .perform(as(1, post("/api/posts/10/likes")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("この投稿は見つかりません"))
        .andExpect(jsonPath("$.errors").isEmpty());
  }

  @Test
  void 投稿IDが数字でなければ400() throws Exception {
    mockMvc
        .perform(as(1, post("/api/posts/abc/likes")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("postId"));
  }

  @Test
  void 使えないメソッドは405() throws Exception {
    mockMvc
        .perform(as(1, get("/api/posts/10/likes")))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
  }
}
