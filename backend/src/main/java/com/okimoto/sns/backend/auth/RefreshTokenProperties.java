package com.okimoto.sns.backend.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * リフレッシュトークンの設定（application.yml の app.refresh-token）。
 *
 * @param expiration 有効期限（14日）。この期間ログイン状態が続く
 * @param cookieSecure Cookie に Secure 属性を付けるか。HTTPS の本番では true、HTTP のローカル開発では false
 * @param reuseGrace 再発行で交換したトークンが、この時間内にもう一度使われたら「同時のリクエスト」とみなして許す猶予時間（10秒）
 */
@ConfigurationProperties("app.refresh-token")
public record RefreshTokenProperties(
    Duration expiration, boolean cookieSecure, Duration reuseGrace) {}
