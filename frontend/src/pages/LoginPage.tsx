import { useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, NETWORK_ERROR_MESSAGE } from '../api/client'
import { useAuth } from '../auth/authContext'
import { FormField } from '../components/FormField'
import styles from './AuthPage.module.css'

interface LoginErrors {
  email?: string
  password?: string
}

/** S-01 ログイン（docs/screens.md・docs/feature-specs/01_auth.md） */
export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<LoginErrors>({})
  const [alert, setAlert] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const passwordRef = useRef<HTMLInputElement>(null)

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const nextErrors: LoginErrors = {}
    if (!email.trim()) nextErrors.email = 'メールアドレスを入力してください'
    if (!password) nextErrors.password = 'パスワードを入力してください'
    setErrors(nextErrors)
    setAlert(null)
    if (Object.keys(nextErrors).length > 0) return

    setSubmitting(true)
    try {
      await login({ email: email.trim(), password })
      navigate('/', { replace: true })
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        // どちらが違うかは教えない。パスワード欄だけ空にする
        setAlert(err.message)
        setPassword('')
        passwordRef.current?.focus()
      } else {
        setAlert(err instanceof ApiError && err.status !== 0 ? err.message : NETWORK_ERROR_MESSAGE)
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>SNSアプリにログイン</h1>
      <form onSubmit={handleSubmit} noValidate>
        {alert && (
          <p className={styles.alert} role="alert">
            {alert}
          </p>
        )}
        <FormField
          id="login-email"
          label="メールアドレス"
          type="email"
          autoComplete="username"
          autoFocus
          value={email}
          onChange={setEmail}
          error={errors.email}
        />
        <FormField
          id="login-password"
          label="パスワード"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={setPassword}
          error={errors.password}
          inputRef={passwordRef}
        />
        <button type="submit" className={styles.submit} disabled={submitting}>
          {submitting ? 'ログイン中…' : 'ログイン'}
        </button>
      </form>
      <p className={styles.switch}>
        アカウントをお持ちでない方は <Link to="/signup">新規登録はこちら</Link>
      </p>
    </main>
  )
}
