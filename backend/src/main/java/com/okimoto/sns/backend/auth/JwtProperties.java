package com.okimoto.sns.backend.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT の設定（application.yml の app.jwt）。
 *
 * @param secret 署名鍵。32バイト（256ビット）以上。環境変数 JWT_SECRET で渡す
 * @param expiration アクセストークンの有効期限（15分。切れたらリフレッシュトークンで再発行する）
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, Duration expiration) {}
