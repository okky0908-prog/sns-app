package com.okimoto.sns.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * リフレッシュトークンの発行・交換（ローテーション）・無効化。
 *
 * <ul>
 *   <li>トークンは推測できないランダムな文字列（256ビット）。DB には SHA-256 のハッシュだけを保存する
 *   <li>使うたびに古いものを無効にして新しいものを発行する（ローテーション）
 *   <li>無効になったトークンがもう一度使われたら、盗まれて使い回された可能性があるので、そのユーザーのリフレッシュトークンをすべて無効にする
 *   <li>ただし、再発行で交換してから猶予時間（10秒）以内の再利用は、2つのタブが同時に再発行した・再発行の最中にリロードした などの
 *       「同時のリクエスト」とみなして、新しいトークンを発行する（全無効化しない）
 * </ul>
 */
@Service
public class RefreshTokenService {

  private static final int TOKEN_BYTES = 32;

  private final RefreshTokenMapper refreshTokenMapper;
  private final RefreshTokenProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public RefreshTokenService(
      RefreshTokenMapper refreshTokenMapper, RefreshTokenProperties properties, Clock clock) {
    this.refreshTokenMapper = refreshTokenMapper;
    this.properties = properties;
    this.clock = clock;
  }

  /** 新しいリフレッシュトークンを発行し、トークンそのもの（ブラウザに渡す値）を返す。 */
  public String issue(long userId) {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    OffsetDateTime now = OffsetDateTime.now(clock);
    refreshTokenMapper.insert(
        new RefreshToken(userId, hash(token), now.plus(properties.expiration()), now));
    return token;
  }

  /**
   * 有効なリフレッシュトークンなら無効にして、新しいものと交換する。
   *
   * @return 交換できたときはユーザーIDと新しいトークン。無効・期限切れ・使い回しのときは空
   */
  // 例外ではなく空を返すので、使い回しを検知したときの「すべて無効にする」処理はロールバックされずに確定する
  @Transactional
  public Optional<Rotation> rotate(String token) {
    Optional<RefreshToken> found = refreshTokenMapper.findByTokenHash(hash(token));
    if (found.isEmpty()) {
      return Optional.empty();
    }
    RefreshToken current = found.get();
    OffsetDateTime now = OffsetDateTime.now(clock);
    if (current.getRevokedAt() != null) {
      return reuseOfRevoked(current, now);
    }
    if (!current.getExpiresAt().isAfter(now)) {
      return Optional.empty();
    }
    if (refreshTokenMapper.rotate(current.getId(), now) == 0) {
      // 読んでから無効にするまでの間に、ほかのリクエストが先に交換した（ほぼ同時の再発行）
      return refreshTokenMapper
          .findByTokenHash(current.getTokenHash())
          .flatMap(latest -> reuseOfRevoked(latest, now));
    }
    return Optional.of(new Rotation(current.getUserId(), issue(current.getUserId())));
  }

  /**
   * すでに無効になっているトークンが使われたとき。
   *
   * <ul>
   *   <li>再発行で交換してから猶予時間以内なら、同時のリクエストとみなして新しいトークンを発行する
   *   <li>それ以外（ログアウト済み、使い回し検知で無効化済み、猶予時間を過ぎた）は盗難の可能性があるので、そのユーザーのトークンをすべて無効にする
   * </ul>
   */
  private Optional<Rotation> reuseOfRevoked(RefreshToken token, OffsetDateTime now) {
    OffsetDateTime rotatedAt = token.getRotatedAt();
    boolean concurrentRequest =
        rotatedAt != null
            && !now.isAfter(rotatedAt.plus(properties.reuseGrace()))
            && token.getExpiresAt().isAfter(now);
    if (concurrentRequest) {
      return Optional.of(new Rotation(token.getUserId(), issue(token.getUserId())));
    }
    refreshTokenMapper.revokeAllByUserId(token.getUserId(), now);
    return Optional.empty();
  }

  /** ログアウト。トークンが見つからなくても何もしない。 */
  public void revoke(String token) {
    refreshTokenMapper
        .findByTokenHash(hash(token))
        .ifPresent(found -> refreshTokenMapper.revoke(found.getId(), OffsetDateTime.now(clock)));
  }

  /** トークンの SHA-256 ハッシュ（16進数64文字） */
  static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 が使えません", e);
    }
  }

  /** 交換の結果 */
  public record Rotation(long userId, String refreshToken) {}
}
