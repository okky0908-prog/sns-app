package com.okimoto.sns.backend.timeline;

import com.okimoto.sns.backend.follow.FollowMapper;
import com.okimoto.sns.backend.post.Post;
import com.okimoto.sns.backend.post.PostEvent;
import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.post.PostResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * タイムラインのリアルタイム通知（SSE）。docs/api.md「A-16 タイムラインの通知」。
 *
 * <ul>
 *   <li>接続中の画面（SseEmitter）をメモリ上に持ち、投稿・編集・削除がコミットされたら全員へ送る
 *   <li>投稿の内容は受け取る人ごとに作る（自分の投稿か＝mine、フォロー中タブに出すか＝inFollowing）
 *   <li>送信は別スレッドで行い、投稿した人のリクエストを待たせない
 * </ul>
 *
 * <p>制約：接続の一覧はこのサーバーのメモリ上にあるので、バックエンドが1台の構成が前提。複数台にするときは Redis Pub/Sub や PostgreSQL の LISTEN/NOTIFY
 * などで、サーバー間で通知を受け渡す必要がある（docs/infrastructure.md）。
 */
@Service
public class TimelineStreamService {

  private static final Logger log = LoggerFactory.getLogger(TimelineStreamService.class);

  /** 1本の接続の最長時間。過ぎたら画面側がつなぎ直す（その時点のアクセストークンで認証し直す） */
  static final Duration CONNECTION_TIMEOUT = Duration.ofMinutes(30);

  private final PostMapper postMapper;
  private final FollowMapper followMapper;
  private final Map<SseEmitter, Long> subscribers = new ConcurrentHashMap<>();
  private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();

  public TimelineStreamService(PostMapper postMapper, FollowMapper followMapper) {
    this.postMapper = postMapper;
    this.followMapper = followMapper;
  }

  /** 画面からの接続を受け付ける */
  public SseEmitter subscribe(long userId) {
    SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT.toMillis());
    subscribers.put(emitter, userId);
    emitter.onCompletion(() -> subscribers.remove(emitter));
    emitter.onTimeout(() -> subscribers.remove(emitter));
    emitter.onError(e -> subscribers.remove(emitter));
    // 接続できたことをすぐに知らせる（レスポンスのヘッダーを画面へ届けるため）
    send(emitter, SseEmitter.event().name("connected").data(Map.of(), MediaType.APPLICATION_JSON));
    return emitter;
  }

  int subscriberCount() {
    return subscribers.size();
  }

  /** DB への保存が確定してから送る（ロールバックされた投稿を通知しない） */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPostEvent(PostEvent event) {
    if (subscribers.isEmpty()) {
      return;
    }
    switch (event) {
      case PostEvent.Created created -> broadcastPost("post-created", created.postId());
      case PostEvent.Updated updated -> broadcastPost("post-updated", updated.postId());
      case PostEvent.Deleted deleted ->
          sendToAll("post-deleted", Map.of("postId", deleted.postId()));
    }
  }

  private void broadcastPost(String eventName, long postId) {
    Optional<Post> found = postMapper.findById(postId);
    if (found.isEmpty()) {
      return; // 通知の前に削除された
    }
    Post post = found.get();
    Set<Long> followers = new HashSet<>(followMapper.findFollowerIds(post.getUserId()));
    subscribers.forEach(
        (emitter, userId) -> {
          boolean inFollowing = userId.equals(post.getUserId()) || followers.contains(userId);
          TimelineStreamEvent data =
              new TimelineStreamEvent(PostResponse.from(post, userId), inFollowing);
          sendAsync(
              emitter, SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        });
  }

  private void sendToAll(String eventName, Object data) {
    subscribers
        .keySet()
        .forEach(
            emitter ->
                sendAsync(
                    emitter,
                    SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON)));
  }

  /** 25秒ごとに「接続中」の合図（コメント行）を送る。ALB やプロキシが無通信の接続を切らないように */
  @Scheduled(fixedRate = 25_000, initialDelay = 25_000)
  public void heartbeat() {
    subscribers.keySet().forEach(emitter -> sendAsync(emitter, SseEmitter.event().comment("ping")));
  }

  private void sendAsync(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    sender.execute(() -> send(emitter, event));
  }

  private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException | IllegalStateException e) {
      // 画面が閉じられた・接続が切れた
      log.debug("タイムラインの通知を送れなかったので接続を閉じます: {}", e.getMessage());
      subscribers.remove(emitter);
      emitter.completeWithError(e);
    }
  }

  @PreDestroy
  void shutdown() {
    subscribers.keySet().forEach(SseEmitter::complete);
    sender.shutdown();
  }
}
