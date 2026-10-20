package com.okimoto.sns.backend.user;

import static com.okimoto.sns.backend.support.ApiErrors.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.web.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** UserService の単体テスト（Mapper・S3 は偽物）。同値分割：見つかる・見つからない、自分のプロフィール・他人のプロフィール。 */
class UserServiceTest {

  private final UserMapper userMapper = mock(UserMapper.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final UserService service = new UserService(userMapper, imageStorage);

  private Profile profile(long id, String iconKey) {
    Profile profile = new Profile();
    profile.setId(id);
    profile.setUsername("alice");
    profile.setDisplayName("アリス");
    profile.setIconKey(iconKey);
    profile.setFollowingCount(2);
    profile.setFollowerCount(3);
    profile.setFollowedByMe(true);
    return profile;
  }

  @Test
  void 他人のプロフィールは_meが偽でアイコンのURLが入る() {
    when(userMapper.findProfile("ALICE", 9)).thenReturn(Optional.of(profile(1, "icons/a.png")));
    when(imageStorage.urlOf("icons/a.png")).thenReturn("https://cdn/icons/a.png");

    ProfileResponse response = service.profile(9, "ALICE");

    assertThat(response.id()).isEqualTo(1);
    assertThat(response.me()).isFalse();
    assertThat(response.iconUrl()).isEqualTo("https://cdn/icons/a.png");
    assertThat(response.followingCount()).isEqualTo(2);
    assertThat(response.followerCount()).isEqualTo(3);
    assertThat(response.followedByMe()).isTrue();
  }

  @Test
  void 自分のプロフィールは_meが真() {
    when(userMapper.findProfile("alice", 1)).thenReturn(Optional.of(profile(1, null)));

    assertThat(service.profile(1, "alice").me()).isTrue();
  }

  @Test
  void いないユーザーのプロフィールは404() {
    when(userMapper.findProfile("nobody", 1)).thenReturn(Optional.empty());

    assertApiError(() -> service.profile(1, "nobody"), ErrorCode.USER_NOT_FOUND);
  }

  @Test
  void ユーザー名からIDを探し_いなければ404() {
    when(userMapper.findIdByUsernameIgnoreCase("alice")).thenReturn(Optional.of(1L));
    when(userMapper.findIdByUsernameIgnoreCase("nobody")).thenReturn(Optional.empty());

    assertThat(service.findIdOrThrow("alice")).isEqualTo(1);
    assertApiError(() -> service.findIdOrThrow("nobody"), ErrorCode.USER_NOT_FOUND);
  }
}
