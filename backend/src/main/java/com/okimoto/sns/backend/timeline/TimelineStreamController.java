package com.okimoto.sns.backend.timeline;

import com.okimoto.sns.backend.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** A-16 タイムラインの通知（SSE）。アクセストークンが必要。 */
@RestController
public class TimelineStreamController {

  private final TimelineStreamService timelineStreamService;

  public TimelineStreamController(TimelineStreamService timelineStreamService) {
    this.timelineStreamService = timelineStreamService;
  }

  @GetMapping(path = "/api/timeline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @AuthenticationPrincipal AuthenticatedUser user, HttpServletResponse response) {
    // 途中のプロキシ（nginx など）にためこまれず、すぐに画面へ届くようにする
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("X-Accel-Buffering", "no");
    return timelineStreamService.subscribe(user.id());
  }
}
