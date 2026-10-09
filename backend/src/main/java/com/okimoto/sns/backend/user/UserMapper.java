package com.okimoto.sns.backend.user;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** users テーブルへのアクセス。SQL は resources/mapper/UserMapper.xml に書く。 */
@Mapper
public interface UserMapper {

  Optional<User> findById(long id);

  /** メールアドレスは小文字にそろえて保存しているので、小文字にしてから渡すこと。 */
  Optional<User> findByEmail(String email);

  boolean existsByUsernameIgnoreCase(String username);

  /** ユーザー名（大文字・小文字を区別しない）からユーザー ID を探す */
  Optional<Long> findIdByUsernameIgnoreCase(String username);

  /** プロフィール（フォロー数・フォロワー数・me がフォロー中か）を1回の SQL で取る。ユーザー名は大文字・小文字を区別しない */
  Optional<Profile> findProfile(@Param("username") String username, @Param("me") long me);

  boolean existsByEmail(String email);

  /** プロフィールを更新する。iconKey が null ならアイコンは今のまま（差し替えたときだけ渡す） */
  void updateProfile(
      @Param("id") long id,
      @Param("displayName") String displayName,
      @Param("bio") String bio,
      @Param("iconKey") String iconKey,
      @Param("now") OffsetDateTime now);

  /** 登録後、採番された ID を user に入れる。 */
  void insert(User user);
}
