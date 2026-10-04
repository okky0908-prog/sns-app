// 新規登録の入力チェック（docs/feature-specs/01_auth.md）。
// サーバー側でも同じチェックをしており、そちらが正。ここでは送信前に早めに知らせるために使う

export interface SignupForm {
  username: string
  displayName: string
  email: string
  password: string
  passwordConfirm: string
}

export type SignupErrors = Partial<Record<keyof SignupForm, string>>

/** 見た目の文字数で数える（絵文字も1文字。サーバーの codePointCount と同じ） */
const countChars = (text: string) => [...text].length

export function validateSignup(form: SignupForm): SignupErrors {
  const errors: SignupErrors = {}
  if (!/^[A-Za-z0-9_]{4,15}$/.test(form.username)) {
    errors.username = 'ユーザー名は半角英数字と_で4〜15文字で入力してください'
  }
  const nameLength = countChars(form.displayName.trim())
  if (nameLength < 1 || nameLength > 50) {
    errors.displayName = '表示名は1〜50文字で入力してください'
  }
  const email = form.email.trim()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 255) {
    errors.email = 'メールアドレスの形式が正しくありません'
  }
  if (form.password.length < 8) {
    errors.password = 'パスワードは8文字以上で入力してください'
  } else if (form.password.length > 72) {
    errors.password = 'パスワードは72文字以内で入力してください'
  }
  if (!form.passwordConfirm) {
    errors.passwordConfirm = 'パスワード（確認）を入力してください'
  } else if (form.passwordConfirm !== form.password) {
    errors.passwordConfirm = 'パスワードが一致しません'
  }
  return errors
}
