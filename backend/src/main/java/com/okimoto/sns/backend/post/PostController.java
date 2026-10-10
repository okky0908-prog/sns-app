package com.okimoto.sns.backend.post;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import com.okimoto.sns.backend.web.CursorPageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

/** 投稿とタイムラインの API（docs/api.md の A-10〜A-17）。すべてログイン（アクセストークン）が必要。 */
@RestController
public class PostController {

  private final PostService postService;

  public PostController(PostService postService) {
    this.postService = postService;
  }

  /** A-10 フォロー中タイムライン（自分＋フォロー中の人の投稿）。続きは前回の nextCursor を cursor に渡す。 */
  @GetMapping("/api/timeline")
  public CursorPageResponse<PostResponse> followingTimeline(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) String cursor) {
    return postService.timeline(user.id(), cursor, true);
  }

  /** A-15 全体タイムライン（全ユーザーの投稿）。続きは前回の nextCursor を cursor に渡す。 */
  @GetMapping("/api/timeline/all")
  public CursorPageResponse<PostResponse> allTimeline(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) String cursor) {
    return postService.timeline(user.id(), cursor, false);
  }

  /** A-16 フォロー中タイムラインの新しい投稿の件数。since は画面が最後に取った一番新しい投稿の ID。 */
  @GetMapping("/api/timeline/new-count")
  public NewPostCountResponse followingNewCount(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestParam long since) {
    return postService.countNewPosts(user.id(), since, true);
  }

  /** A-17 全体タイムラインの新しい投稿の件数。 */
  @GetMapping("/api/timeline/all/new-count")
  public NewPostCountResponse allNewCount(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestParam long since) {
    return postService.countNewPosts(user.id(), since, false);
  }

  /**
   * A-11 投稿作成（multipart/form-data）。本文（content）と画像（images、4枚まで）のどちらか一方は必要。
   *
   * <p>入力のチェックは PostService で行う（本文と画像を合わせて判定するため）。
   */
  @PostMapping(path = "/api/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public PostResponse create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) String content,
      @RequestParam(required = false) List<MultipartFile> images) {
    return postService.create(user.id(), content, images);
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
