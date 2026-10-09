package com.okimoto.sns.backend.user;

import com.okimoto.sns.backend.web.TextLength;

/**
 * プロフィール編集（A-62）のテキストの項目。multipart/form-data のパートとして受け取る（アイコン画像は別に受け取る）。
 *
 * <p>文字数は前後の空白・改行を除いて数える（絵文字も1文字。投稿と同じ数え方）。
 *
 * @param displayName 表示名（1〜50文字）
 * @param bio 自己紹介（160文字まで。空でもよい）
 */
public record ProfileUpdateRequest(
    @TextLength(min = 1, max = 50, message = "表示名は1〜50文字で入力してください") String displayName,
    @TextLength(max = 160, message = "自己紹介は160文字以内で入力してください") String bio) {

  /**
   * 改行をそろえる。ブラウザはフォーム（multipart）で送るとき改行を「\r\n」に変えるので、入力チェックの前に「\n」にする。
   * そのままだと改行1つを2文字と数えてしまい、画面の文字数表示とずれるうえ、投稿・コメント（\n）と保存の形がそろわない
   */
  public ProfileUpdateRequest {
    displayName = normalizeNewlines(displayName);
    bio = normalizeNewlines(bio);
  }

  private static String normalizeNewlines(String value) {
    return value == null ? null : value.replace("\r\n", "\n").replace('\r', '\n');
  }
}
