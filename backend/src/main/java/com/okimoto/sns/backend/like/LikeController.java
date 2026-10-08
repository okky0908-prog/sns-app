package com.okimoto.sns.backend.like;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** いいねの API（docs/api.md の A-40・A-41）。ログイン（アクセストークン）が必要。 */
@RestController
public class LikeController {

  private final LikeService likeService;

  public LikeController(LikeService likeService) {
    this.likeService = likeService;
  }

  /** A-40 いいねする（いいね済みでもエラーにしない）。 */
  @PostMapping("/api/posts/{postId}/likes")
  public LikeResponse like(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long postId) {
    return likeService.like(user.id(), postId);
  }

  /** A-41 いいねを取り消す（いいねしていなくてもエラーにしない）。 */
  @DeleteMapping("/api/posts/{postId}/likes")
  public LikeResponse unlike(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long postId) {
    return likeService.unlike(user.id(), postId);
  }
}
