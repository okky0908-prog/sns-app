package com.okimoto.sns.backend.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** refresh_tokens テーブルへのアクセス。SQL は resources/mapper/RefreshTokenMapper.xml に書く。 */
@Mapper
public interface RefreshTokenMapper {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  void insert(RefreshToken token);

  /**
   * 無効にする。
   *
   * @return 無効にした件数。0 ならすでに無効だった
   */
  int revoke(@Param("id") long id, @Param("now") OffsetDateTime now);

  /** ユーザーの有効なリフレッシュトークンをすべて無効にする。 */
  int revokeAllByUserId(@Param("userId") long userId, @Param("now") OffsetDateTime now);
}
