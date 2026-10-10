package com.okimoto.sns.backend.user;

import java.time.OffsetDateTime;

/**
 * ユーザー検索の結果の1行。並び順とカーソルに使う値（一致の度合い・登録日時）も一緒に持つ。
 *
 * <p>matchRank は、キーワードありの検索のときだけ入る（0：ユーザー名が完全一致、1：前方一致、2：それ以外）。
 */
public class FoundUser {

  private long id;
  private String username;
  private String displayName;
  private String bio;
  private String iconKey;
  private OffsetDateTime createdAt;
  private boolean followedByMe;
  private int matchRank;

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

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public boolean isFollowedByMe() {
    return followedByMe;
  }

  public void setFollowedByMe(boolean followedByMe) {
    this.followedByMe = followedByMe;
  }

  public int getMatchRank() {
    return matchRank;
  }

  public void setMatchRank(int matchRank) {
    this.matchRank = matchRank;
  }
}
