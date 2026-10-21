package com.okimoto.sns.backend.user;

import static com.okimoto.sns.backend.support.TestTokens.as;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import com.okimoto.sns.backend.post.PostService;
import com.okimoto.sns.backend.support.ControllerTest;
import com.okimoto.sns.backend.support.TestTokens;
import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.CursorPageResponse;
import com.okimoto.sns.backend.web.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * UserController（A-60〜A-62・A-70）。テスト計画書 5.3 の基本ケース（Service は偽物）。
 *
 * <p>境界値分析（プロフィール編集）：表示名は前後の空白を除いて1〜50文字（絵文字も1文字）、自己紹介は160文字まで。
 */
@ControllerTest(UserController.class)
class UserControllerWebMvcTest {

  private static final ProfileResponse PROFILE =
      new ProfileResponse(1, "alice", "アリス", "自己紹介", null, 2, 3, false, true);

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;
  @MockitoBean private UserService userService;
  @MockitoBean private ProfileEditService profileEditService;
  @MockitoBean private UserSearchService userSearchService;
  @MockitoBean private PostService postService;

  @BeforeEach
  void setUp() {
    TestTokens.teach(jwtService);
  }

  private static MockMultipartHttpServletRequestBuilder updateProfile() {
    return as(1, multipart(HttpMethod.PUT, "/api/users/me"));
  }

  // ===== 検索・プロフィール・投稿一覧 =====

  @Test
  void 検索は200で_キーワードとカーソルをそのままServiceに渡す() throws Exception {
    when(userSearchService.search(1, " @yama ", "c1"))
        .thenReturn(
            new CursorPageResponse<>(
                List.of(new UserListItemResponse(2, "yamada", "山田", null, null, false, false)),
                null,
                false));

    mockMvc
        .perform(as(1, get("/api/users/search").param("q", " @yama ").param("cursor", "c1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].username").value("yamada"));
  }

  @Test
  void 検索のキーワードが長すぎればServiceの入力エラーを400で返す() throws Exception {
    when(userSearchService.search(eq(1L), any(), isNull()))
        .thenThrow(
            ApiException.badRequest(
                List.of(new ApiError.FieldError("q", "検索キーワードは50文字以内で入力してください"))));

    mockMvc
        .perform(as(1, get("/api/users/search").param("q", "あ".repeat(51))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("q"));
  }

  @Test
  void プロフィールは200_いないユーザーは404() throws Exception {
    when(userService.profile(1, "alice")).thenReturn(PROFILE);
    when(userService.profile(1, "nobody")).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND));

    mockMvc
        .perform(as(1, get("/api/users/alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.followingCount").value(2))
        .andExpect(jsonPath("$.followerCount").value(3))
        .andExpect(jsonPath("$.me").value(true))
        .andExpect(jsonPath("$.iconUrl").isEmpty());
    mockMvc
        .perform(as(1, get("/api/users/nobody")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  void そのユーザーの投稿一覧は_ユーザー名からIDを探して投稿を取る() throws Exception {
    when(userService.findIdOrThrow("alice")).thenReturn(5L);
    when(postService.userPosts(1, 5, "c1"))
        .thenReturn(new CursorPageResponse<>(List.of(), null, false));

    mockMvc
        .perform(as(1, get("/api/users/alice/posts").param("cursor", "c1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void searchは検索のURLとして扱い_searchという名前のプロフィールとは取り違えない() throws Exception {
    when(userSearchService.search(1, null, null))
        .thenReturn(new CursorPageResponse<>(List.of(), null, false));

    mockMvc.perform(as(1, get("/api/users/search"))).andExpect(status().isOk());

    verify(userService, never()).profile(anyLong(), any());
  }

  // ===== プロフィール編集（multipart） =====

  @Test
  void 編集は表示名と自己紹介とアイコンをServiceに渡し_200で更新後のプロフィールを返す() throws Exception {
    when(profileEditService.update(eq(1L), any(), any())).thenReturn(PROFILE);
    MockMultipartFile icon = new MockMultipartFile("icon", "me.png", "image/png", new byte[] {1});

    mockMvc
        .perform(updateProfile().file(icon).param("displayName", "アリス").param("bio", "1行目\r\n2行目"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("アリス"));

    verify(profileEditService)
        .update(
            eq(1L),
            argThat(r -> r.displayName().equals("アリス") && r.bio().equals("1行目\n2行目")),
            argThat(file -> file.getOriginalFilename().equals("me.png")));
  }

  @Test
  void アイコンを送らなければnullを渡す() throws Exception {
    mockMvc.perform(updateProfile().param("displayName", "アリス")).andExpect(status().isOk());

    verify(profileEditService).update(eq(1L), argThat(r -> r.bio() == null), isNull());
  }

  @ParameterizedTest(name = "{0}「{1}」は受け付ける")
  @CsvSource({"displayName, あ", "bio, ''"})
  void 境界の内側は受け付ける(String field, String value) throws Exception {
    mockMvc
        .perform(updateProfile().param("displayName", "アリス").param(field, value))
        .andExpect(status().isOk());
  }

  @Test
  void 表示名は絵文字50個まで_自己紹介は160文字まで受け付ける() throws Exception {
    mockMvc
        .perform(
            updateProfile().param("displayName", "🎉".repeat(50)).param("bio", "あ".repeat(160)))
        .andExpect(status().isOk());
  }

  @ParameterizedTest(name = "{0} が {1} 文字なら 400「{2}」")
  @CsvSource({
    "displayName, 0, 表示名は1〜50文字で入力してください",
    "displayName, 51, 表示名は1〜50文字で入力してください",
    "bio, 161, 自己紹介は160文字以内で入力してください"
  })
  void 文字数の違反は400で_Serviceを呼ばない(String field, int length, String message) throws Exception {
    MockMultipartHttpServletRequestBuilder request =
        updateProfile().param(field, "あ".repeat(length));
    if (!field.equals("displayName")) {
      request.param("displayName", "アリス");
    }

    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].message", contains(message)));
    verify(profileEditService, never()).update(anyLong(), any(), any());
  }

  @Test
  void 表示名を送らない_空白だけなら400() throws Exception {
    mockMvc
        .perform(updateProfile().param("bio", "自己紹介"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("displayName"));
    mockMvc
        .perform(updateProfile().param("displayName", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("displayName"));
  }

  @Test
  void ログインなしなら4つともすべて401で_Serviceを呼ばない() throws Exception {
    mockMvc.perform(get("/api/users/search")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/users/alice")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/users/alice/posts")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(multipart(HttpMethod.PUT, "/api/users/me").param("displayName", "アリス"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    verifyNoInteractions(userService, profileEditService, userSearchService, postService);
  }
}
