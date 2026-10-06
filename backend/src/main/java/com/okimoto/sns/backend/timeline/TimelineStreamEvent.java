package com.okimoto.sns.backend.timeline;

import com.okimoto.sns.backend.post.PostResponse;

/**
 * 「post-created」「post-updated」で送る中身。
 *
 * @param post 受け取る人から見た投稿（mine は受け取る人の投稿かどうか）
 * @param inFollowing 受け取る人のフォロー中タブに出す投稿か（受け取る人自身の投稿、またはフォロー中の人の投稿）
 */
public record TimelineStreamEvent(PostResponse post, boolean inFollowing) {}
