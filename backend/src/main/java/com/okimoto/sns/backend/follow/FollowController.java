package com.okimoto.sns.backend.follow;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import com.okimoto.sns.backend.user.UserListItemResponse;
import com.okimoto.sns.backend.web.CursorPageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** フォローの API（docs/api.md の A-50〜A-53）。ログイン（アクセストークン）が必要。 */
@RestController
public class FollowController {

  private final FollowService followService;

  public FollowController(FollowService followService) {
    this.followService = followService;
  }

  /** A-50 フォローする（フォロー済みでもエラーにしない。自分自身は 400）。 */
  @PostMapping("/api/users/{username}/follow")
  public FollowResponse follow(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String username) {
    return followService.follow(user.id(), username);
  }

  /** A-51 フォローを解除する（フォローしていなくてもエラーにしない）。 */
  @DeleteMapping("/api/users/{username}/follow")
  public FollowResponse unfollow(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String username) {
    return followService.unfollow(user.id(), username);
  }

  /** A-52 フォロー中一覧（フォローした日時の新しい順）。続きは前回の nextCursor を cursor に渡す。 */
  @GetMapping("/api/users/{username}/following")
  public CursorPageResponse<UserListItemResponse> following(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String username,
      @RequestParam(required = false) String cursor) {
    return followService.following(user.id(), username, cursor);
  }

  /** A-53 フォロワー一覧（フォローされた日時の新しい順）。 */
  @GetMapping("/api/users/{username}/followers")
  public CursorPageResponse<UserListItemResponse> followers(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String username,
      @RequestParam(required = false) String cursor) {
    return followService.followers(user.id(), username, cursor);
  }
}
