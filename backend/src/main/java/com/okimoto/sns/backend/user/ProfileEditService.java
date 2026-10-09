package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.storage.ImageStorage;
import com.okimoto.sns.backend.storage.ImageType;
import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ApiException;
import com.okimoto.sns.backend.web.ErrorCode;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;

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

  private static final Logger log = LoggerFactory.getLogger(ProfileEditService.class);

  /** アイコン画像の上限（5MB） */
  static final long MAX_ICON_BYTES = 5L * 1024 * 1024;

  static final String ICON_TYPE_ERROR = "jpg・png・gif の画像を選択してください";
  static final String ICON_SIZE_ERROR = "5MB以下の画像を選択してください";

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
      newIconKey = uploadIcon(icon);
      cleanUpIconsAfterTransaction(user.getIconKey(), newIconKey);
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

  /** 形式と大きさを確かめてから S3 の icons/{UUID}.{拡張子} に保存し、そのキーを返す */
  private String uploadIcon(MultipartFile icon) {
    if (icon.getSize() > MAX_ICON_BYTES) {
      throw iconError(ICON_SIZE_ERROR);
    }
    byte[] bytes;
    try {
      bytes = icon.getBytes();
    } catch (IOException e) {
      throw new ApiException(ErrorCode.MALFORMED_REQUEST);
    }
    ImageType type = ImageType.detect(bytes).orElseThrow(() -> iconError(ICON_TYPE_ERROR));
    String key = "icons/" + UUID.randomUUID() + "." + type.extension();
    try {
      imageStorage.upload(key, bytes, type.contentType());
    } catch (SdkException e) {
      log.error("アイコン画像を保存できませんでした: {}", key, e);
      throw new ApiException(ErrorCode.IMAGE_UPLOAD_FAILED);
    }
    return key;
  }

  /** トランザクションが終わったときに、使わなくなった画像を削除する（コミット：古い画像、取り消し：新しい画像） */
  private void cleanUpIconsAfterTransaction(String oldKey, String newKey) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
              if (oldKey != null) {
                imageStorage.deleteQuietly(oldKey);
              }
            } else {
              imageStorage.deleteQuietly(newKey);
            }
          }
        });
  }

  private static ApiException iconError(String message) {
    return ApiException.badRequest(List.of(new ApiError.FieldError("icon", message)));
  }
}
