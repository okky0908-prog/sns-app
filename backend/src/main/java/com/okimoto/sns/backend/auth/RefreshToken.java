package com.okimoto.sns.backend.auth;

import java.time.OffsetDateTime;

/** refresh_tokens テーブルの1行。トークンそのものではなく、そのハッシュを持つ。 */
public class RefreshToken {

  private Long id;
  private Long userId;
  private String tokenHash;
  private OffsetDateTime expiresAt;
  private OffsetDateTime revokedAt;
  private OffsetDateTime createdAt;

  public RefreshToken() {}

  public RefreshToken(
      long userId, String tokenHash, OffsetDateTime expiresAt, OffsetDateTime createdAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(OffsetDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public OffsetDateTime getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(OffsetDateTime revokedAt) {
    this.revokedAt = revokedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
