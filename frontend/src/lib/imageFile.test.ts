import { describe, expect, test } from 'vitest'
import { IMAGE_ACCEPT, IMAGE_MAX_BYTES, imageFileError } from './imageFile'

const TYPE_ERROR = 'jpg・png・gif の画像を選択してください'
const SIZE_ERROR = '5MB以下の画像を選択してください'

/** 指定した形式と大きさのファイル */
const file = (type: string, size = 10) => new File([new Uint8Array(size)], 'image', { type })

describe('imageFileError：選んだ画像のチェック（サーバーの UploadedImage と同じ決まり）', () => {
  test.each(['image/jpeg', 'image/png', 'image/gif'])('%s は選べる', (type) => {
    expect(imageFileError(file(type))).toBeNull()
  })

  test.each(['image/webp', 'image/heic', 'image/svg+xml', 'application/pdf', ''])('「%s」は選べない', (type) => {
    expect(imageFileError(file(type))).toBe(TYPE_ERROR)
  })

  test('大きさは 5MB ちょうどまで選べ、1バイトでも超えたら選べない', () => {
    expect(IMAGE_MAX_BYTES).toBe(5 * 1024 * 1024)
    expect(imageFileError(file('image/png', IMAGE_MAX_BYTES))).toBeNull()
    expect(imageFileError(file('image/png', IMAGE_MAX_BYTES + 1))).toBe(SIZE_ERROR)
  })

  test('形式も大きさも違うときは、形式のエラーを先に出す', () => {
    expect(imageFileError(file('image/webp', IMAGE_MAX_BYTES + 1))).toBe(TYPE_ERROR)
  })

  test('ファイル選択の画面で絞り込む形式は、選べる形式と同じ', () => {
    expect(IMAGE_ACCEPT).toBe('image/jpeg,image/png,image/gif')
  })
})
