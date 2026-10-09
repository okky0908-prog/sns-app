package com.okimoto.sns.backend.follow;

import java.time.OffsetDateTime;

/**
 * フォロー中・フォロワー一覧の1行（follows の1行と、一覧に出す相手のユーザー）。
 *
 * <p>followId・followedAt は並び順とカーソルに使う（フォローした日時の新しい順）。
 */
public class FollowedUser {

  private long followId;
  private OffsetDateTime followedAt;
  private long id;
  private String username;
  private String displayName;
  private String bio;
  private String iconKey;
  private boolean followedByMe;

  public long getFollowId() {
    return followId;
  }

  public void setFollowId(long followId) {
    this.followId = followId;
  }

  public OffsetDateTime getFollowedAt() {
    return followedAt;
  }

  public void setFollowedAt(OffsetDateTime followedAt) {
    this.followedAt = followedAt;
  }

  public long getId() {
    return id;
  }

  public void setId(long id) {
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

  public boolean isFollowedByMe() {
    return followedByMe;
  }

  public void setFollowedByMe(boolean followedByMe) {
    this.followedByMe = followedByMe;
  }
}
