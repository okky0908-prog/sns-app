package com.okimoto.sns.backend.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * ログイン用トークン（JWT）の発行と検証。
 *
 * <p>トークンに入れるのはユーザーID（subject）と発行日時・有効期限だけ。メールアドレスなどの個人情報は入れない。
 */
@Service
public class JwtService {

  static final int MIN_SECRET_BYTES = 32;

  private final SecretKey key;
  private final JwtProperties properties;
  private final Clock clock;
  private final JwtParser parser;

  public JwtService(JwtProperties properties, Clock clock) {
    this.key = toKey(properties.secret());
    this.properties = properties;
    this.clock = clock;
    this.parser = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build();
  }

  public String issue(long userId) {
    Instant now = clock.instant();
    return Jwts.builder()
        .subject(Long.toString(userId))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(properties.expiration())))
        .signWith(key)
        .compact();
  }

  /** 署名と有効期限が正しければユーザーIDを返す。改ざん・期限切れ・形式の誤りは空を返す。 */
  public Optional<Long> parseUserId(String token) {
    try {
      String subject = parser.parseSignedClaims(token).getPayload().getSubject();
      return Optional.of(Long.parseLong(subject));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static SecretKey toKey(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "JWT の署名鍵が設定されていません。.env または環境変数 JWT_SECRET に32バイト以上のランダムな文字列を設定してください"
              + "（例：openssl rand -base64 48）");
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException("JWT の署名鍵が短すぎます（" + bytes.length + "バイト）。32バイト以上にしてください");
    }
    return Keys.hmacShaKeyFor(bytes);
  }
}
