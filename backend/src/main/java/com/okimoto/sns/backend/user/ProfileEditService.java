package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.storage.UploadedImage;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * プロフィール編集（docs/feature-specs/07_profile.md の F-61）。更新できるのはログイン中のユーザー自身だけ。
 *
 * <p>アイコンを差し替えるときの順番：
 *
 * <ol>
 *   <li>新しい画像を S3 に保存する。失敗したら、表示名・自己紹介も更新せずにエラーにする
 *   <li>DB を更新する
 *   <li>コミットできたら古い画像を S3 から削除する（先に消すと、DB の更新が失敗したときに画像だけ消えてしまうため）。 DB
 *       の更新が失敗して取り消されたら、保存したばかりの新しい画像を削除する（どこからも使われない画像を残さないため）
 * </ol>
 */
@Service
public class ProfileEditService {

  private final UserMapper userMapper;
  private final UserService userService;
  private final ImageStorage imageStorage;
  private final Clock clock;

  public ProfileEditService(
      UserMapper userMapper, UserService userService, ImageStorage imageStorage, Clock clock) {
    this.userMapper = userMapper;
    this.userService = userService;
    this.imageStorage = imageStorage;
    this.clock = clock;
  }

  /**
   * @param icon 新しいアイコン画像。送られてこなければ（null・空）アイコンは今のまま
   * @return 更新後のプロフィール（A-60 と同じ形）
   */
  @Transactional
  public ProfileResponse update(long me, ProfileUpdateRequest request, MultipartFile icon) {
    User user =
        userMapper.findById(me).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    String newIconKey = null;
    if (icon != null && !icon.isEmpty()) {
      newIconKey = imageStorage.store("icons/", UploadedImage.from(icon, "icon"));
      // コミットされたら古いアイコンを、取り消されたら保存したばかりの新しいアイコンを削除する
      imageStorage.deleteAfterTransaction(
          user.getIconKey() == null ? List.of() : List.of(user.getIconKey()), List.of(newIconKey));
    }
    String bio = request.bio() == null ? "" : request.bio().strip();
    userMapper.updateProfile(
        me,
        request.displayName().strip(),
        bio.isEmpty() ? null : bio,
        newIconKey,
        OffsetDateTime.now(clock));
    return userService.profile(me, user.getUsername());
  }
}
