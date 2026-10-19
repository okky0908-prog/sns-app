import { describe, expect, test } from 'vitest'
import { validateSignup, type SignupForm } from './validation'

/** すべての項目が正しい入力。テストでは1項目ずつ変えて確かめる */
const valid: SignupForm = {
  username: 'yamada_01',
  displayName: '山田太郎',
  email: 'yamada@example.com',
  password: 'password123',
  passwordConfirm: 'password123',
}

const errorsOf = (change: Partial<SignupForm>) => validateSignup({ ...valid, ...change })

describe('validateSignup：新規登録の入力チェック（docs/feature-specs/01_auth.md）', () => {
  test('正しい入力ならエラーなし', () => {
    expect(validateSignup(valid)).toEqual({})
  })

  test('ユーザー名は半角英数字と_で4〜15文字', () => {
    expect(errorsOf({ username: 'abcd' }).username).toBeUndefined()
    expect(errorsOf({ username: 'a'.repeat(15) }).username).toBeUndefined()
    expect(errorsOf({ username: 'abc' }).username).toBe(
      'ユーザー名は半角英数字と_で4〜15文字で入力してください',
    )
    expect(errorsOf({ username: 'a'.repeat(16) }).username).toBeDefined()
    expect(errorsOf({ username: 'yamada-01' }).username).toBeDefined()
  })

  test('表示名は前後の空白を除いて1〜50文字（絵文字も1文字）', () => {
    expect(errorsOf({ displayName: 'あ'.repeat(50) }).displayName).toBeUndefined()
    // 絵文字50個は50文字（JavaScript の length では100になるが、エラーにしない）
    expect(errorsOf({ displayName: '🎉'.repeat(50) }).displayName).toBeUndefined()
    expect(errorsOf({ displayName: `  ${'あ'.repeat(50)}  ` }).displayName).toBeUndefined()
    expect(errorsOf({ displayName: 'あ'.repeat(51) }).displayName).toBe('表示名は1〜50文字で入力してください')
    expect(errorsOf({ displayName: '   ' }).displayName).toBe('表示名は1〜50文字で入力してください')
  })

  test('メールアドレスは形式が正しく255文字以内', () => {
    expect(errorsOf({ email: 'a@b.c' }).email).toBeUndefined()
    expect(errorsOf({ email: 'yamada.example.com' }).email).toBe('メールアドレスの形式が正しくありません')
    expect(errorsOf({ email: `${'a'.repeat(245)}@example.com` }).email).toBeDefined()
  })

  test('パスワードは8〜72文字', () => {
    const password = (value: string) => errorsOf({ password: value, passwordConfirm: value }).password
    expect(password('a'.repeat(8))).toBeUndefined()
    expect(password('a'.repeat(72))).toBeUndefined()
    expect(password('a'.repeat(7))).toBe('パスワードは8文字以上で入力してください')
    expect(password('a'.repeat(73))).toBe('パスワードは72文字以内で入力してください')
  })

  test('パスワード（確認）は入力必須で、パスワードと一致する', () => {
    expect(errorsOf({ passwordConfirm: '' }).passwordConfirm).toBe('パスワード（確認）を入力してください')
    expect(errorsOf({ passwordConfirm: 'password124' }).passwordConfirm).toBe('パスワードが一致しません')
  })
})
