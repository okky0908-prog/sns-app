package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import com.okimoto.sns.backend.post.PostResponse;
import com.okimoto.sns.backend.post.PostService;
import com.okimoto.sns.backend.web.CursorPageResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** プロフィール・ユーザー検索の API（docs/api.md の A-60〜A-62・A-70）。ログイン（アクセストークン）が必要。 */
@RestController
public class UserController {

  private final UserService userService;
  private final ProfileEditService profileEditService;
  private final UserSearchService userSearchService;
  private final PostService postService;

  public UserController(
      UserService userService,
      ProfileEditService profileEditService,
      UserSearchService userSearchService,
      PostService postService) {
    this.userService = userService;
    this.profileEditService = profileEditService;
    this.userSearchService = userSearchService;
    this.postService = postService;
  }

  /**
   * A-70 ユーザー検索。q が空なら最近参加したユーザー。続きは前回の nextCursor を cursor に渡す。
   *
   * <p>URL の形が A-60（/api/users/{username}）と同じだが、Spring は文字どおりの /search を優先する。 そのため search
   * というユーザー名は新規登録で使えないようにしている（AuthService）。
   */
  @GetMapping("/api/users/search")
  public CursorPageResponse<UserListItemResponse> search(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor) {
    return userSearchService.search(user.id(), q, cursor);
  }

  /**
   * A-62 自分のプロフィールの編集（multipart/form-data）。表示名・自己紹介と、送ったときだけアイコン画像を差し替える。
   *
   * <p>URL にユーザー名を入れず me にしているのは、ログイン中のユーザー以外を更新する方法をなくすため。
   */
  @PutMapping(path = "/api/users/me", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ProfileResponse update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @ModelAttribute ProfileUpdateRequest request,
      @RequestParam(required = false) MultipartFile icon) {
    return profileEditService.update(user.id(), request, icon);
  }

  /** A-60 プロフィール。 */
  @GetMapping("/api/users/{username}")
  public ProfileResponse profile(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String username) {
    return userService.profile(user.id(), username);
  }

  /** A-61 そのユーザーの投稿一覧（新しい順）。続きは前回の nextCursor を cursor に渡す。 */
  @GetMapping("/api/users/{username}/posts")
  public CursorPageResponse<PostResponse> posts(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String username,
      @RequestParam(required = false) String cursor) {
    return postService.userPosts(user.id(), userService.findIdOrThrow(username), cursor);
  }
}
