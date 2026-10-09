package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import com.okimoto.sns.backend.post.PostResponse;
import com.okimoto.sns.backend.post.PostService;
import com.okimoto.sns.backend.web.CursorPageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** プロフィールの API（docs/api.md の A-60・A-61）。ログイン（アクセストークン）が必要。 */
@RestController
public class UserController {

  private final UserService userService;
  private final PostService postService;

  public UserController(UserService userService, PostService postService) {
    this.userService = userService;
    this.postService = postService;
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
