package com.okimoto.sns.backend.user;

import static com.okimoto.sns.backend.support.ApiErrors.assertApiError;
import static com.okimoto.sns.backend.support.ApiErrors.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.storage.UploadedImage;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;

/**
 * ProfileEditService の単体テスト（Mapper・UserService・S3 は偽物）。デシジョンテーブル（アイコン × 今のアイコン × 行のロック）で考える。
 *
 * <table>
 *   <tr><th>アイコン</th><th>今のアイコン</th><th>行のロック</th><th>結果</th></tr>
 *   <tr><td>なし・空</td><td>−</td><td>成功</td><td>アイコンはそのまま（S3 に触らない）</td></tr>
 *   <tr><td>あり</td><td>あり</td><td>成功</td><td>保存し、確定したら古いもの・取り消したら新しいものを消す</td></tr>
 *   <tr><td>あり</td><td>なし</td><td>成功</td><td>保存し、取り消したら新しいものを消す</td></tr>
 *   <tr><td>あり</td><td>−</td><td>ユーザーがいない・失敗</td><td>保存したばかりの新しいアイコンをその場で消す</td></tr>
 *   <tr><td>画像でない</td><td>−</td><td>−</td><td>400（S3 に保存しない）</td></tr>
 * </table>
 *
 * <p>同値分割：自己紹介（空白だけ → null・前後の空白を除く・null → null）。
 */
class ProfileEditServiceTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};

  private final UserMapper userMapper = mock(UserMapper.class);
  private final UserService userService = mock(UserService.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final ProfileEditService service =
      new ProfileEditService(
          userMapper, userService, imageStorage, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

  private final ProfileResponse profile =
      new ProfileResponse(1, "alice", "アリス", null, null, 0, 0, false, true);

  @BeforeEach
  void setUp() {
    when(userService.profile(1, "alice")).thenReturn(profile);
  }

  private void currentUser(String iconKey) {
    User user = new User("alice", "前の名前", "alice@example.com", "hash", NOW);
    user.setId(1L);
    user.setIconKey(iconKey);
    when(userMapper.findByIdForUpdate(1)).thenReturn(Optional.of(user));
  }

  private static MockMultipartFile icon(byte[] bytes) {
    return new MockMultipartFile("icon", "me.png", "image/png", bytes);
  }

  @Test
  void アイコンなしなら_S3に触らずアイコンはそのまま_更新後のプロフィールを返す() {
    currentUser("icons/old.png");

    ProfileResponse response = service.update(1, new ProfileUpdateRequest(" アリス ", "自己紹介"), null);

    verify(userMapper).updateProfile(1, "アリス", "自己紹介", null, NOW);
    verify(imageStorage, never()).store(anyString(), any());
    verify(imageStorage, never()).deleteAfterTransaction(any(), any());
    assertThat(response).isSameAs(profile);
  }

  @Test
  void 空のファイルはアイコンなしとして扱う() {
    currentUser("icons/old.png");

    service.update(1, new ProfileUpdateRequest("アリス", null), icon(new byte[0]));

    verify(imageStorage, never()).store(anyString(), any());
    verify(userMapper).updateProfile(1, "アリス", null, null, NOW);
  }

  @Test
  void アイコンありで今のアイコンもあれば_確定したら古いもの_取り消したら新しいものを消す() {
    currentUser("icons/old.png");
    when(imageStorage.store(eq("icons/"), any(UploadedImage.class))).thenReturn("icons/new.png");

    service.update(1, new ProfileUpdateRequest("アリス", null), icon(PNG));

    verify(imageStorage).deleteAfterTransaction(List.of("icons/old.png"), List.of("icons/new.png"));
    verify(userMapper).updateProfile(1, "アリス", null, "icons/new.png", NOW);
  }

  @Test
  void アイコンありで今のアイコンがなければ_取り消したときだけ新しいものを消す() {
    currentUser(null);
    when(imageStorage.store(eq("icons/"), any(UploadedImage.class))).thenReturn("icons/new.png");

    service.update(1, new ProfileUpdateRequest("アリス", null), icon(PNG));

    verify(imageStorage).deleteAfterTransaction(List.of(), List.of("icons/new.png"));
  }

  @Test
  void ユーザーがいなければ401で_保存したばかりのアイコンをその場で消す() {
    when(userMapper.findByIdForUpdate(1)).thenReturn(Optional.empty());
    when(imageStorage.store(eq("icons/"), any(UploadedImage.class))).thenReturn("icons/new.png");

    assertApiError(
        () -> service.update(1, new ProfileUpdateRequest("アリス", null), icon(PNG)),
        ErrorCode.UNAUTHENTICATED);
    verify(imageStorage).deleteQuietly("icons/new.png");
    verify(userMapper, never()).updateProfile(anyLong(), any(), any(), any(), any());
  }

  @Test
  void 行のロックでDBのエラーが起きても_保存したばかりのアイコンを消してからエラーにする() {
    DataAccessResourceFailureException dbDown =
        new DataAccessResourceFailureException("DB につながらない");
    when(userMapper.findByIdForUpdate(1)).thenThrow(dbDown);
    when(imageStorage.store(eq("icons/"), any(UploadedImage.class))).thenReturn("icons/new.png");

    assertThatThrownBy(() -> service.update(1, new ProfileUpdateRequest("アリス", null), icon(PNG)))
        .isSameAs(dbDown);
    verify(imageStorage).deleteQuietly("icons/new.png");
  }

  @Test
  void アイコンなしでユーザーがいなければ401で_消すものはない() {
    when(userMapper.findByIdForUpdate(1)).thenReturn(Optional.empty());

    assertApiError(
        () -> service.update(1, new ProfileUpdateRequest("アリス", null), null),
        ErrorCode.UNAUTHENTICATED);
    verify(imageStorage, never()).deleteQuietly(anyString());
  }

  @Test
  void 画像でないファイルは400で_S3に保存しない() {
    assertFieldError(
        () -> service.update(1, new ProfileUpdateRequest("アリス", null), icon("画像ではない".getBytes())),
        "icon",
        UploadedImage.TYPE_ERROR);
    verify(imageStorage, never()).store(anyString(), any());
  }

  @ParameterizedTest(name = "自己紹介「{0}」は「{1}」で保存する")
  @CsvSource(
      value = {"'  ' | <null>", "' 自己紹介 ' | 自己紹介", "<null> | <null>"},
      delimiter = '|',
      nullValues = "<null>")
  void 自己紹介は前後の空白を除き_空ならnullで保存する(String bio, String saved) {
    currentUser(null);

    service.update(1, new ProfileUpdateRequest("アリス", bio), null);

    verify(userMapper).updateProfile(1, "アリス", saved, null, NOW);
  }
}
