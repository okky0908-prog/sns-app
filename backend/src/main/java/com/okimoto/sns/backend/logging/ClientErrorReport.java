package com.okimoto.sns.backend.logging;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 画面で起きたエラーの報告（A-90）。ログを大きくしすぎないよう、それぞれ長さの上限を決める（画面側で上限まで切ってから送る）。
 *
 * @param kind どこで拾ったか。render：画面の表示中（React）、error：捕まえ損ねた例外、unhandledrejection：捕まえ損ねた Promise の失敗
 * @param message エラーの文言
 * @param stack スタックトレース（ないこともある）
 * @param path エラーが起きた画面のパス（URL の検索条件は含めない）
 */
public record ClientErrorReport(
    @NotNull
        @Pattern(regexp = "render|error|unhandledrejection")
        @Schema(allowableValues = {"render", "error", "unhandledrejection"})
        String kind,
    @NotBlank @Size(max = MESSAGE_MAX) String message,
    @Size(max = STACK_MAX) @Schema(nullable = true) String stack,
    @NotNull @Pattern(regexp = "/.*") @Size(max = PATH_MAX) String path) {

  public static final int MESSAGE_MAX = 1000;
  public static final int STACK_MAX = 8000;
  public static final int PATH_MAX = 200;
}
