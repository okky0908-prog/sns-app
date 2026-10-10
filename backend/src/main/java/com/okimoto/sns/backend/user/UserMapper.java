package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.web.CreatedAtCursor;
import java.time.OffsetDateTime;
import java.util.List;
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

  /**
   * キーワードでの検索（A-70）。@ユーザー名・表示名の部分一致（大文字・小文字を区別しない）。並び順は一致の度合い → ユーザー名（小文字）。
   *
   * @param keyword 前後の空白・先頭の@を取り除いたキーワード（完全一致の判定に使う）
   * @param pattern keyword の % _ \ をエスケープしたもの（ILIKE に使う）
   * @param cursor 前回の最後の行。null なら先頭から
   */
  List<FoundUser> search(
      @Param("keyword") String keyword,
      @Param("pattern") String pattern,
      @Param("me") long me,
      @Param("cursor") SearchCursor cursor,
      @Param("limit") int limit);

  /** 最近参加したユーザー（キーワードが空のとき。登録日時の新しい順） */
  List<FoundUser> findRecent(
      @Param("me") long me, @Param("cursor") CreatedAtCursor cursor, @Param("limit") int limit);

  /** 登録後、採番された ID を user に入れる。 */
  void insert(User user);
}
