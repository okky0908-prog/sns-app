package com.okimoto.sns.backend.comment;

import java.time.OffsetDateTime;

/**
 * comments テーブルの1行。
 *
 * <p>一覧・1件を取るときは users を JOIN するので、書いた人の情報（author〜）も一緒に入る。INSERT のときは使わない。
 */
public class Comment {

  private Long id;
  private Long postId;
  private Long userId;
  private String content;
  private OffsetDateTime createdAt;
  private String authorUsername;
  private String authorDisplayName;
  private String authorIconKey;

  public Comment() {}

  public Comment(long postId, long userId, String content, OffsetDateTime now) {
    this.postId = postId;
    this.userId = userId;
    this.content = content;
    this.createdAt = now;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getPostId() {
    return postId;
  }

  public void setPostId(Long postId) {
    this.postId = postId;
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
