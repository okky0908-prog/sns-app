package com.okimoto.sns.backend.follow;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** フォローの API（docs/api.md の A-50・A-51）。ログイン（アクセストークン）が必要。 */
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
}
