package com.okimoto.sns.backend.user;

/** プロフィールの取得結果（users の1行に、フォロー数・フォロワー数・ログイン中のユーザーがフォロー中かを足したもの）。 */
public class Profile {

  private long id;
  private String username;
  private String displayName;
  private String bio;
  private String iconKey;
  private long followingCount;
  private long followerCount;
  private boolean followedByMe;

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

  public long getFollowingCount() {
    return followingCount;
  }

  public void setFollowingCount(long followingCount) {
    this.followingCount = followingCount;
  }

  public long getFollowerCount() {
    return followerCount;
  }

  public void setFollowerCount(long followerCount) {
    this.followerCount = followerCount;
  }

  public boolean isFollowedByMe() {
    return followedByMe;
  }

  public void setFollowedByMe(boolean followedByMe) {
    this.followedByMe = followedByMe;
  }
}
