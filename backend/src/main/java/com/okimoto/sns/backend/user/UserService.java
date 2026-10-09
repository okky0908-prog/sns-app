package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** プロフィールの表示（docs/feature-specs/07_profile.md）と、ユーザー名からユーザーを探す処理。 */
@Service
public class UserService {

  private final UserMapper userMapper;
  private final ImageStorage imageStorage;

  public UserService(UserMapper userMapper, ImageStorage imageStorage) {
    this.userMapper = userMapper;
    this.imageStorage = imageStorage;
  }

  /** プロフィール。ユーザー名は大文字・小文字を区別しない（/users/Yamada でも yamada のページを開く） */
  @Transactional(readOnly = true)
  public ProfileResponse profile(long me, String username) {
    return userMapper
        .findProfile(username, me)
        .map(profile -> ProfileResponse.from(profile, me, imageStorage.urlOf(profile.getIconKey())))
        .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
  }

  /** ユーザー名（大文字・小文字を区別しない）からユーザー ID を探す。いなければ 404 */
  @Transactional(readOnly = true)
  public long findIdOrThrow(String username) {
    return userMapper
        .findIdByUsernameIgnoreCase(username)
        .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
  }
}
