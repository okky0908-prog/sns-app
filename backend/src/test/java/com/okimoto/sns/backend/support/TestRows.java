package com.okimoto.sns.backend.support;

import com.okimoto.sns.backend.post.Post;
import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.user.User;
import com.okimoto.sns.backend.user.UserMapper;
import java.time.OffsetDateTime;

/**
 * Mapper のテストの準備で使う行を作る。日時を指定できるので、並び順やカーソルの境目を確かめられる。
 *
 * <p>ユーザー・投稿の登録の SQL そのもののテストは、UserMapperTest・PostMapperTest で行う。
 */
public class TestRows {

  /** テストの基準の日時 */
  public static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-21T00:00:00Z");

  private final UserMapper userMapper;
  private final PostMapper postMapper;

  public TestRows(UserMapper userMapper, PostMapper postMapper) {
    this.userMapper = userMapper;
    this.postMapper = postMapper;
  }

  /** ユーザーを登録して ID を返す。表示名は「ユーザー名の名前」、メールアドレスは「ユーザー名@example.com」（小文字） */
  public long user(String username) {
    return user(username, T0);
  }

  public long user(String username, OffsetDateTime createdAt) {
    User user =
        new User(
            username,
            username + "の名前",
            username.toLowerCase() + "@example.com",
            "dummy-hash",
            createdAt);
    userMapper.insert(user);
    return user.getId();
  }

  /** 投稿を登録して ID を返す */
  public long post(long userId, String content, OffsetDateTime createdAt) {
    Post post = new Post(userId, content, createdAt);
    postMapper.insert(post);
    return post.getId();
  }
}
