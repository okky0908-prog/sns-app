package com.okimoto.sns.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okimoto.sns.backend.auth.RefreshTokenService.Rotation;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * RefreshTokenService の単体テスト（Mapper は偽物）。状態遷移（docs/test-plan.md 4.4）で考える。
 *
 * <table>
 *   <tr><th>今の状態</th><th>再発行したときの結果</th></tr>
 *   <tr><td>有効</td><td>交換して新しいトークンを発行</td></tr>
 *   <tr><td>期限切れ（期限ちょうどを含む）</td><td>再発行しない</td></tr>
 *   <tr><td>交換済み（10秒以内。10秒ちょうどを含む）</td><td>同時のリクエストとして新しいトークンを発行（全無効化しない）</td></tr>
 *   <tr><td>交換済み（10秒より後）・ログアウト済み</td><td>使い回しとして、そのユーザーのトークンをすべて無効</td></tr>
 *   <tr><td>知らないトークン</td><td>再発行しない（全無効化もしない）</td></tr>
 * </table>
 *
 * <p>エラー推測：2つのタブが同時に再発行し、読んでから交換するまでの間にほかのリクエストが先に交換した。
 */
class RefreshTokenServiceTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T00:00:00Z");
  private static final Duration GRACE = Duration.ofSeconds(10);
  private static final String TOKEN = "the-refresh-token";

  private final RefreshTokenMapper mapper = mock(RefreshTokenMapper.class);
  private final RefreshTokenService service =
      new RefreshTokenService(
          mapper,
          new RefreshTokenProperties(Duration.ofDays(14), false, GRACE),
          Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

  /** DB の行。revokedAt・rotatedAt が null なら有効 */
  private RefreshToken row(
      OffsetDateTime expiresAt, OffsetDateTime revokedAt, OffsetDateTime rotatedAt) {
    RefreshToken token =
        new RefreshToken(7, RefreshTokenService.hash(TOKEN), expiresAt, NOW.minusDays(1));
    token.setId(100L);
    token.setRevokedAt(revokedAt);
    token.setRotatedAt(rotatedAt);
    return token;
  }

  private void found(RefreshToken token) {
    when(mapper.findByTokenHash(RefreshTokenService.hash(TOKEN))).thenReturn(Optional.of(token));
  }

  // ===== 発行 =====

  @Test
  void 発行すると_ハッシュだけを期限14日で保存し_トークンそのものを返す() {
    String token = service.issue(7);

    verify(mapper)
        .insert(
            argThat(
                row ->
                    row.getUserId() == 7
                        && row.getTokenHash().equals(RefreshTokenService.hash(token))
                        && !row.getTokenHash().equals(token)
                        && row.getExpiresAt().equals(NOW.plusDays(14))
                        && row.getCreatedAt().equals(NOW)));
    // 32バイトの乱数を Base64（URL 用・= なし）にしたもの
    assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(service.issue(7)).isNotEqualTo(token);
  }

  @Test
  void ハッシュはSHA256の16進数64文字() {
    assertThat(RefreshTokenService.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  // ===== 再発行（状態遷移） =====

  @Test
  void 有効なトークンは_交換して新しいトークンを発行する() {
    found(row(NOW.plusDays(1), null, null));
    when(mapper.rotate(100, NOW)).thenReturn(1);

    Optional<Rotation> rotation = service.rotate(TOKEN);

    assertThat(rotation).isPresent();
    assertThat(rotation.get().userId()).isEqualTo(7);
    assertThat(rotation.get().refreshToken()).isNotEqualTo(TOKEN);
    verify(mapper).insert(any());
    verify(mapper, never()).revokeAllByUserId(anyLong(), any());
  }

  @Test
  void 期限ちょうどのトークンは期限切れで_再発行しない() {
    found(row(NOW, null, null));

    assertThat(service.rotate(TOKEN)).isEmpty();
    verify(mapper, never()).rotate(anyLong(), any());
    verify(mapper, never()).insert(any());
  }

  @Test
  void 期限の直前のトークンは_まだ再発行できる() {
    found(row(NOW.plusNanos(1000), null, null));
    when(mapper.rotate(100, NOW)).thenReturn(1);

    assertThat(service.rotate(TOKEN)).isPresent();
  }

  @Test
  void 知らないトークンは_再発行も全無効化もしない() {
    when(mapper.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThat(service.rotate(TOKEN)).isEmpty();
    verify(mapper, never()).revokeAllByUserId(anyLong(), any());
    verify(mapper, never()).insert(any());
  }

  @Test
  void 交換してから10秒ちょうどまでの再利用は_同時のリクエストとして新しいトークンを発行する() {
    found(row(NOW.plusDays(1), NOW.minus(GRACE), NOW.minus(GRACE)));

    assertThat(service.rotate(TOKEN)).isPresent();
    verify(mapper, never()).revokeAllByUserId(anyLong(), any());
    verify(mapper).insert(any());
  }

  @Test
  void 交換してから10秒を過ぎた再利用は_使い回しとしてすべて無効にする() {
    OffsetDateTime rotatedAt = NOW.minus(GRACE).minusNanos(1000);
    found(row(NOW.plusDays(1), rotatedAt, rotatedAt));

    assertThat(service.rotate(TOKEN)).isEmpty();
    verify(mapper).revokeAllByUserId(7, NOW);
    verify(mapper, never()).insert(any());
  }

  @Test
  void ログアウト済みのトークンの再利用は_使い回しとしてすべて無効にする() {
    found(row(NOW.plusDays(1), NOW.minusSeconds(1), null));

    assertThat(service.rotate(TOKEN)).isEmpty();
    verify(mapper).revokeAllByUserId(7, NOW);
  }

  @Test
  void 猶予時間の中でも期限切れなら_同時のリクエストとは扱わずすべて無効にする() {
    found(row(NOW, NOW.minusSeconds(1), NOW.minusSeconds(1)));

    assertThat(service.rotate(TOKEN)).isEmpty();
    verify(mapper).revokeAllByUserId(7, NOW);
  }

  @Test
  void 読んでから交換するまでの間にほかのリクエストが先に交換したら_同時のリクエストとして新しいトークンを発行する() {
    RefreshToken before = row(NOW.plusDays(1), null, null);
    RefreshToken afterOtherRequest = row(NOW.plusDays(1), NOW, NOW);
    when(mapper.findByTokenHash(RefreshTokenService.hash(TOKEN)))
        .thenReturn(Optional.of(before))
        .thenReturn(Optional.of(afterOtherRequest));
    when(mapper.rotate(100, NOW)).thenReturn(0); // 先に交換されていた

    assertThat(service.rotate(TOKEN)).isPresent();
    verify(mapper, times(2)).findByTokenHash(any());
    verify(mapper, never()).revokeAllByUserId(anyLong(), any());
  }

  @Test
  void 読んでから交換するまでの間に行が消えていたら_再発行しない() {
    when(mapper.findByTokenHash(RefreshTokenService.hash(TOKEN)))
        .thenReturn(Optional.of(row(NOW.plusDays(1), null, null)))
        .thenReturn(Optional.empty());
    when(mapper.rotate(100, NOW)).thenReturn(0);

    assertThat(service.rotate(TOKEN)).isEmpty();
    verify(mapper, never()).insert(any());
  }

  // ===== ログアウト・期限切れの削除 =====

  @Test
  void ログアウトは見つかったトークンだけ無効にし_見つからなくても何もしない() {
    found(row(NOW.plusDays(1), null, null));
    service.revoke(TOKEN);
    verify(mapper).revoke(100, NOW);

    when(mapper.findByTokenHash(RefreshTokenService.hash("unknown"))).thenReturn(Optional.empty());
    service.revoke("unknown");
    verify(mapper, times(1)).revoke(anyLong(), any());
  }

  @Test
  void 期限切れの削除は今の日時で消し_消した件数を返す() {
    when(mapper.deleteExpired(NOW)).thenReturn(3);

    assertThat(service.deleteExpired()).isEqualTo(3);
  }
}
