package com.okimoto.sns.backend.comment;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import com.okimoto.sns.backend.web.CursorPageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** コメントの API（docs/api.md の A-30〜A-32）。ログイン（アクセストークン）が必要。 */
@RestController
public class CommentController {

  private final CommentService commentService;

  public CommentController(CommentService commentService) {
    this.commentService = commentService;
  }

  /** A-30 コメント一覧（古い順）。続きは前回の nextCursor を cursor に渡す。 */
  @GetMapping("/api/posts/{postId}/comments")
  public CursorPageResponse<CommentResponse> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long postId,
      @RequestParam(required = false) String cursor) {
    return commentService.list(user.id(), postId, cursor);
  }

  /** A-31 コメント投稿。投稿後のコメント数も返す。 */
  @PostMapping("/api/posts/{postId}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedCommentResponse create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long postId,
      @Valid @RequestBody CommentRequest request) {
    return commentService.create(user.id(), postId, request.content());
  }

  /** A-32 コメント削除（本人だけ）。削除後のコメント数を返す。 */
  @DeleteMapping("/api/comments/{commentId}")
  public CommentCountResponse delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long commentId) {
    return commentService.delete(user.id(), commentId);
  }
}
