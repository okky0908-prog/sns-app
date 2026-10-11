package com.okimoto.sns.backend.web;

/** 利用者が入力した文章の整え方（投稿・コメント・プロフィールで共通）。 */
public final class Texts {

  private Texts() {}

  /**
   * 改行を「\n」にそろえる。ブラウザはフォーム（multipart）で送るとき改行を「\r\n」に変え、API を直接呼ぶ人は「\r」だけを送ることもある。
   * そろえないと、改行1つを2文字と数えて画面の文字数表示とずれたり、保存される形がばらばらになったりする。 入力チェック（文字数を数える）の前に呼ぶ
   *
   * @return null なら null
   */
  public static String normalizeNewlines(String value) {
    return value == null ? null : value.replace("\r\n", "\n").replace('\r', '\n');
  }
}
