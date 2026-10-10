package com.okimoto.sns.backend.post;

import java.time.OffsetDateTime;

/** post_images テーブルの1行（投稿の画像1枚）。 */
public class PostImage {

  private Long id;
  private Long postId;
  private String imageKey;
  private int sortOrder;
  private OffsetDateTime createdAt;

  public PostImage() {}

  public PostImage(long postId, String imageKey, int sortOrder, OffsetDateTime now) {
    this.postId = postId;
    this.imageKey = imageKey;
    this.sortOrder = sortOrder;
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

  public String getImageKey() {
    return imageKey;
  }

  public void setImageKey(String imageKey) {
    this.imageKey = imageKey;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
