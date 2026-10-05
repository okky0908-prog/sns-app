package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import com.okimoto.sns.backend.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 投稿とタイムラインの API（docs/api.md の A-10〜A-15）。すべてログイン（アクセストークン）が必要。 */
@RestController
public class PostController {

  private final PostService postService;

  public PostController(PostService postService) {
    this.postService = postService;
  }

  /** A-10 フォロー中タイムライン（自分＋フォロー中の人の投稿）。 */
  @GetMapping("/api/timeline")
  public PageResponse<PostResponse> followingTimeline(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "page は0以上で指定してください") int page) {
    return postService.timeline(user.id(), page, true);
  }

  /** A-15 全体タイムライン（全ユーザーの投稿）。 */
  @GetMapping("/api/timeline/all")
  public PageResponse<PostResponse> allTimeline(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "page は0以上で指定してください") int page) {
    return postService.timeline(user.id(), page, false);
  }

  /** A-11 投稿作成（今回はテキストのみ。画像対応時に multipart に変える）。 */
  @PostMapping("/api/posts")
  @ResponseStatus(HttpStatus.CREATED)
  public PostResponse create(
      @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody PostRequest request) {
    return postService.create(user.id(), request.content());
  }

  /** A-12 投稿詳細。 */
  @GetMapping("/api/posts/{postId}")
  public PostResponse get(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long postId) {
    return postService.get(user.id(), postId);
  }

  /** A-13 投稿編集（本文のみ。本人だけ）。 */
  @PutMapping("/api/posts/{postId}")
  public PostResponse update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable long postId,
      @Valid @RequestBody PostRequest request) {
    return postService.update(user.id(), postId, request.content());
  }

  /** A-14 投稿削除（本人だけ）。 */
  @DeleteMapping("/api/posts/{postId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long postId) {
    postService.delete(user.id(), postId);
  }
}
