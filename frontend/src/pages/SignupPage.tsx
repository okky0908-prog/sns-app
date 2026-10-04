import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, NETWORK_ERROR_MESSAGE } from '../api/client'
import { useAuth } from '../auth/authContext'
import { validateSignup, type SignupErrors, type SignupForm } from '../auth/validation'
import { FormField } from '../components/FormField'
import styles from './AuthPage.module.css'

const EMPTY_FORM: SignupForm = { username: '', displayName: '', email: '', password: '', passwordConfirm: '' }

/** S-02 新規登録（docs/screens.md・docs/feature-specs/01_auth.md） */
export function SignupPage() {
  const { signup } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState<SignupForm>(EMPTY_FORM)
  const [errors, setErrors] = useState<SignupErrors>({})
  const [alert, setAlert] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const update = (key: keyof SignupForm) => (value: string) => setForm((prev) => ({ ...prev, [key]: value }))

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const nextErrors = validateSignup(form)
    setErrors(nextErrors)
    setAlert(null)
    if (Object.keys(nextErrors).length > 0) return

    setSubmitting(true)
    try {
      await signup({
        username: form.username,
        displayName: form.displayName.trim(),
        email: form.email.trim(),
        password: form.password,
      })
      navigate('/', { replace: true })
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors.length > 0) {
        // サーバーの入力エラー（400）・重複（409）を、該当する項目の下に出す
        const serverErrors: SignupErrors = {}
        for (const { field, message } of err.fieldErrors) {
          if (field in EMPTY_FORM) {
            serverErrors[field as keyof SignupForm] ??= message
          }
        }
        setErrors(serverErrors)
      } else {
        setAlert(err instanceof ApiError && err.status !== 0 ? err.message : NETWORK_ERROR_MESSAGE)
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>アカウント作成</h1>
      <form onSubmit={handleSubmit} noValidate>
        {alert && (
          <p className={styles.alert} role="alert">
            {alert}
          </p>
        )}
        <FormField
          id="signup-username"
          label="ユーザー名"
          prefix="@"
          autoComplete="username"
          autoFocus
          maxLength={15}
          value={form.username}
          onChange={update('username')}
          error={errors.username}
        />
        <FormField
          id="signup-displayName"
          label="表示名"
          autoComplete="nickname"
          value={form.displayName}
          onChange={update('displayName')}
          error={errors.displayName}
        />
        <FormField
          id="signup-email"
          label="メールアドレス"
          type="email"
          autoComplete="email"
          value={form.email}
          onChange={update('email')}
          error={errors.email}
        />
        <FormField
          id="signup-password"
          label="パスワード（8文字以上）"
          type="password"
          autoComplete="new-password"
          value={form.password}
          onChange={update('password')}
          error={errors.password}
        />
        <FormField
          id="signup-passwordConfirm"
          label="パスワード（確認）"
          type="password"
          autoComplete="new-password"
          value={form.passwordConfirm}
          onChange={update('passwordConfirm')}
          error={errors.passwordConfirm}
        />
        <button type="submit" className={styles.submit} disabled={submitting}>
          {submitting ? '登録中…' : '登録する'}
        </button>
      </form>
      <p className={styles.switch}>
        <Link to="/login">ログインはこちら</Link>
      </p>
    </main>
  )
}
