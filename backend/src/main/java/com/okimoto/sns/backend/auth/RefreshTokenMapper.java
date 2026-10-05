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
   * ログアウトで無効にする。
   *
   * @return 無効にした件数。0 ならすでに無効だった
   */
  int revoke(@Param("id") long id, @Param("now") OffsetDateTime now);

  /**
   * 再発行で新しいトークンに交換して無効にする（revoked_at と rotated_at を入れる）。
   *
   * @return 無効にした件数。0 ならすでに無効だった（同時に使われた）
   */
  int rotate(@Param("id") long id, @Param("now") OffsetDateTime now);

  /** ユーザーの有効なリフレッシュトークンをすべて無効にする。 */
  int revokeAllByUserId(@Param("userId") long userId, @Param("now") OffsetDateTime now);
}
