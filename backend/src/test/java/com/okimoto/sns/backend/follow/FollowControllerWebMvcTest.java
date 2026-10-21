package com.okimoto.sns.backend.follow;

import static com.okimoto.sns.backend.support.TestTokens.as;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.support.ControllerTest;
import com.okimoto.sns.backend.support.TestTokens;
import com.okimoto.sns.backend.user.UserListItemResponse;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** FollowController（A-50〜A-53）。テスト計画書 5.3 の基本ケース（Service は偽物）。 */
@ControllerTest(FollowController.class)
class FollowControllerWebMvcTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @MockitoBean private FollowService followService;

  @BeforeEach
  void setUp() {
    TestTokens.teach(jwtService);
  }

  @Test
  void フォロー_解除すると200で_フォロー状態と相手のフォロワー数を返す() throws Exception {
    when(followService.follow(1, "bobby")).thenReturn(new FollowResponse(true, 9));
    when(followService.unfollow(1, "bobby")).thenReturn(new FollowResponse(false, 8));

    mockMvc
        .perform(as(1, post("/api/users/bobby/follow")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true))
        .andExpect(jsonPath("$.followerCount").value(9));
    mockMvc
        .perform(as(1, delete("/api/users/bobby/follow")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(false))
        .andExpect(jsonPath("$.followerCount").value(8));
  }

  @Test
  void 自分自身のフォローは400_相手がいなければ404() throws Exception {
    when(followService.follow(1, "alice"))
        .thenThrow(new ApiException(ErrorCode.CANNOT_FOLLOW_SELF));
    when(followService.follow(1, "nobody")).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND));

    mockMvc
        .perform(as(1, post("/api/users/alice/follow")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CANNOT_FOLLOW_SELF"));
    mockMvc
        .perform(as(1, post("/api/users/nobody/follow")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void フォロー中一覧とフォロワー一覧は200で_各行の値とカーソルを返す() throws Exception {
    UserListItemResponse row =
        new UserListItemResponse(2, "bobby", "ボブ", null, "https://cdn/2.png", true, false);
    when(followService.following(1, "alice", "c1"))
        .thenReturn(new CursorPageResponse<>(List.of(row), "c2", true));
    when(followService.followers(1, "alice", null))
        .thenReturn(new CursorPageResponse<>(List.of(), null, false));

    mockMvc
        .perform(as(1, get("/api/users/alice/following").param("cursor", "c1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].username").value("bobby"))
        .andExpect(jsonPath("$.items[0].bio").isEmpty())
        .andExpect(jsonPath("$.items[0].iconUrl").value("https://cdn/2.png"))
        .andExpect(jsonPath("$.items[0].followedByMe").value(true))
        .andExpect(jsonPath("$.items[0].me").value(false))
        .andExpect(jsonPath("$.nextCursor").value("c2"));
    mockMvc
        .perform(as(1, get("/api/users/alice/followers")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void ログインなしなら4つともすべて401で_Serviceを呼ばない() throws Exception {
    mockMvc.perform(post("/api/users/bobby/follow")).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/users/bobby/follow")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/users/bobby/following")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/users/bobby/followers"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    verifyNoInteractions(followService);
  }
}
