package com.okimoto.sns.backend.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * リフレッシュトークンの設定（application.yml の app.refresh-token）。
 *
 * @param expiration 有効期限（14日）。この期間ログイン状態が続く
 * @param cookieSecure Cookie に Secure 属性を付けるか。HTTPS の本番では true、HTTP のローカル開発では false
 */
@ConfigurationProperties("app.refresh-token")
public record RefreshTokenProperties(Duration expiration, boolean cookieSecure) {}
