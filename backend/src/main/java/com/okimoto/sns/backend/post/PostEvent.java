package com.okimoto.sns.backend.post;

/** 投稿に起きた変化。PostService が発行し、DB への保存が確定（コミット）してから TimelineStreamService が画面へ通知する。 */
public sealed interface PostEvent {

  long postId();

  record Created(long postId) implements PostEvent {}

  record Updated(long postId) implements PostEvent {}

  record Deleted(long postId) implements PostEvent {}
}
