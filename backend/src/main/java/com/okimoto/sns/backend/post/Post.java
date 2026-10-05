package com.okimoto.sns.backend.post;

import java.time.OffsetDateTime;

/**
 * posts テーブルの1行。
 *
 * <p>一覧・詳細を取るときは users を JOIN するので、投稿者の情報（author〜）も一緒に入る。INSERT のときは使わない。
 */
public class Post {

  private Long id;
  private Long userId;
  private String content;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private OffsetDateTime editedAt;
  private String authorUsername;
  private String authorDisplayName;
  private String authorIconKey;

  public Post() {}

  public Post(long userId, String content, OffsetDateTime now) {
    this.userId = userId;
    this.content = content;
    this.createdAt = now;
    this.updatedAt = now;
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

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public OffsetDateTime getEditedAt() {
    return editedAt;
  }

  public void setEditedAt(OffsetDateTime editedAt) {
    this.editedAt = editedAt;
  }

  public String getAuthorUsername() {
    return authorUsername;
  }

  public void setAuthorUsername(String authorUsername) {
    this.authorUsername = authorUsername;
  }

  public String getAuthorDisplayName() {
    return authorDisplayName;
  }

  public void setAuthorDisplayName(String authorDisplayName) {
    this.authorDisplayName = authorDisplayName;
  }

  public String getAuthorIconKey() {
    return authorIconKey;
  }

  public void setAuthorIconKey(String authorIconKey) {
    this.authorIconKey = authorIconKey;
  }
}
