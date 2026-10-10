// 選んだ画像ファイルのチェック（プロフィールのアイコンと投稿の画像で共通。サーバーの UploadedImage と同じ決まり）

/** 選べる画像の形式。最終的な判定はサーバーがファイルの中身で行う（ここでは明らかに違うものを先に止める） */
export const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif']
export const IMAGE_ACCEPT = IMAGE_TYPES.join(',')
/** 1枚の上限（5MB） */
export const IMAGE_MAX_BYTES = 5 * 1024 * 1024

/** 選べない画像ならエラーメッセージ、選べるなら null */
export function imageFileError(file: File): string | null {
  if (!IMAGE_TYPES.includes(file.type)) return 'jpg・png・gif の画像を選択してください'
  if (file.size > IMAGE_MAX_BYTES) return '5MB以下の画像を選択してください'
  return null
}
