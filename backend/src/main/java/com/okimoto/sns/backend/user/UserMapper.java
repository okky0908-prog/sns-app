package com.okimoto.sns.backend.user;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

/** users テーブルへのアクセス。SQL は resources/mapper/UserMapper.xml に書く。 */
@Mapper
public interface UserMapper {

  Optional<User> findById(long id);

  /** メールアドレスは小文字にそろえて保存しているので、小文字にしてから渡すこと。 */
  Optional<User> findByEmail(String email);

  boolean existsByUsernameIgnoreCase(String username);

  boolean existsByEmail(String email);

  /** 登録後、採番された ID を user に入れる。 */
  void insert(User user);
}
