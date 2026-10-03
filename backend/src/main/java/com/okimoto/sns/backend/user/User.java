package com.okimoto.sns.backend.user;

import java.time.OffsetDateTime;

/** users テーブルの1行。MyBatis が列名（スネークケース）をフィールド（キャメルケース）に詰める。 */
public class User {

  private Long id;
  private String username;
  private String displayName;
  private String email;
  private String passwordHash;
  private String bio;
  private String iconKey;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public User() {}

  public User(
      String username, String displayName, String email, String passwordHash, OffsetDateTime now) {
    this.username = username;
    this.displayName = displayName;
    this.email = email;
    this.passwordHash = passwordHash;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public String getBio() {
    return bio;
  }

  public void setBio(String bio) {
    this.bio = bio;
  }

  public String getIconKey() {
    return iconKey;
  }

  public void setIconKey(String iconKey) {
    this.iconKey = iconKey;
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
}
