package com.okimoto.sns.backend.auth;

import static com.okimoto.sns.backend.support.TestRows.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okimoto.sns.backend.post.PostMapper;
import com.okimoto.sns.backend.support.MapperTest;
import com.okimoto.sns.backend.support.TestRows;
import com.okimoto.sns.backend.user.UserMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

/**
 * RefreshTokenMapper の SQL（6本）。状態遷移（docs/test-plan.md 4.4）で考える。
 *
 * <ul>
 *   <li>有効 →（ログアウト）→ 無効、有効 →（再発行）→ 交換済み。すでに無効なものへの操作は 0件（同時のリクエストを見分けるのに使う）
 *   <li>使い回しの検知：そのユーザーのものをすべて無効にし、交換済みの印（rotated_at）も消す。ほかのユーザーのものは変えない
 *   <li>境界値分析：期限ちょうどは期限切れとして削除する
 * </ul>
 */
@MapperTest
class RefreshTokenMapperTest {

  private static final OffsetDateTime EXPIRES = T0.plus(Duration.ofDays(14));

  @Autowired private RefreshTokenMapper refreshTokenMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private PostMapper postMapper;

  private long alice;
  private long bob;

  @BeforeEach
  void setUp() {
    TestRows rows = new TestRows(userMapper, postMapper);
    alice = rows.user("alice");
    bob = rows.user("bobby");
  }

  private RefreshToken insert(long userId, String hash, OffsetDateTime expiresAt) {
    RefreshToken token = new RefreshToken(userId, hash, expiresAt, T0);
    refreshTokenMapper.insert(token);
    return token;
  }

  private RefreshToken find(String hash) {
    return refreshTokenMapper.findByTokenHash(hash).orElseThrow();
  }

  @Test
  void 登録すると_ハッシュで同じ内容が取れ_有効な状態() {
    RefreshToken token = insert(alice, "a".repeat(64), EXPIRES);

    RefreshToken found = find("a".repeat(64));

    assertThat(found.getId()).isEqualTo(token.getId());
    assertThat(found.getUserId()).isEqualTo(alice);
    assertThat(found.getExpiresAt()).isEqualTo(EXPIRES);
    assertThat(found.getCreatedAt()).isEqualTo(T0);
    assertThat(found.getRevokedAt()).isNull();
    assertThat(found.getRotatedAt()).isNull();
  }

  @Test
  void 知らないハッシュは見つからず_同じハッシュは2つ登録できない() {
    insert(alice, "a".repeat(64), EXPIRES);

    assertThat(refreshTokenMapper.findByTokenHash("b".repeat(64))).isEmpty();
    assertThatThrownBy(() -> insert(bob, "a".repeat(64), EXPIRES))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void ログアウトで無効にすると1件_すでに無効なら0件で無効にした日時は変えない() {
    RefreshToken token = insert(alice, "a".repeat(64), EXPIRES);

    assertThat(refreshTokenMapper.revoke(token.getId(), T0.plusMinutes(1))).isEqualTo(1);
    assertThat(refreshTokenMapper.revoke(token.getId(), T0.plusMinutes(2))).isZero();

    RefreshToken found = find("a".repeat(64));
    assertThat(found.getRevokedAt()).isEqualTo(T0.plusMinutes(1));
    assertThat(found.getRotatedAt()).isNull(); // ログアウトは交換ではない
  }

  @Test
  void 再発行で交換すると1件で交換日時が入り_すでに無効なら0件() {
    RefreshToken token = insert(alice, "a".repeat(64), EXPIRES);

    assertThat(refreshTokenMapper.rotate(token.getId(), T0.plusMinutes(1))).isEqualTo(1);
    // 2つのタブが同時に再発行したとき、あとから来た方は 0件になり「同時のリクエスト」と分かる
    assertThat(refreshTokenMapper.rotate(token.getId(), T0.plusMinutes(1))).isZero();

    RefreshToken found = find("a".repeat(64));
    assertThat(found.getRevokedAt()).isEqualTo(T0.plusMinutes(1));
    assertThat(found.getRotatedAt()).isEqualTo(T0.plusMinutes(1));
  }

  @Test
  void ログアウト済みのトークンは交換できない() {
    RefreshToken token = insert(alice, "a".repeat(64), EXPIRES);
    refreshTokenMapper.revoke(token.getId(), T0.plusMinutes(1));

    assertThat(refreshTokenMapper.rotate(token.getId(), T0.plusMinutes(2))).isZero();
    assertThat(find("a".repeat(64)).getRotatedAt()).isNull();
  }

  @Test
  void 使い回しを検知したら_そのユーザーのものをすべて無効にし交換済みの印も消す() {
    insert(alice, "valid".repeat(12) + "0000", EXPIRES); // 有効
    RefreshToken rotated = insert(alice, "rotat".repeat(12) + "0000", EXPIRES);
    refreshTokenMapper.rotate(rotated.getId(), T0.plusMinutes(1)); // 交換済み（猶予時間の対象）
    RefreshToken loggedOut = insert(alice, "logou".repeat(12) + "0000", EXPIRES);
    refreshTokenMapper.revoke(loggedOut.getId(), T0.plusMinutes(1)); // ログアウト済み
    insert(bob, "bobby".repeat(12) + "0000", EXPIRES); // ほかのユーザー

    int changed = refreshTokenMapper.revokeAllByUserId(alice, T0.plusMinutes(5));

    assertThat(changed).isEqualTo(2); // 有効なもの・交換済みのもの。ログアウト済みのものは変えない
    RefreshToken valid = find("valid".repeat(12) + "0000");
    assertThat(valid.getRevokedAt()).isEqualTo(T0.plusMinutes(5));
    RefreshToken wasRotated = find("rotat".repeat(12) + "0000");
    assertThat(wasRotated.getRevokedAt()).isEqualTo(T0.plusMinutes(1)); // 無効にした日時はそのまま
    assertThat(wasRotated.getRotatedAt()).isNull(); // 猶予時間で再発行できないようにする
    assertThat(find("logou".repeat(12) + "0000").getRevokedAt()).isEqualTo(T0.plusMinutes(1));
    assertThat(find("bobby".repeat(12) + "0000").getRevokedAt()).isNull();
  }

  @Test
  void 期限ちょうどまでの行を削除し_期限内の行は残す() {
    insert(alice, "past".repeat(16), T0.minusSeconds(1));
    insert(alice, "just".repeat(16), T0);
    insert(alice, "futu".repeat(16), T0.plusNanos(1000));

    int deleted = refreshTokenMapper.deleteExpired(T0);

    assertThat(deleted).isEqualTo(2);
    assertThat(refreshTokenMapper.findByTokenHash("futu".repeat(16))).isPresent();
    assertThat(refreshTokenMapper.findByTokenHash("just".repeat(16))).isEmpty();
  }
}
