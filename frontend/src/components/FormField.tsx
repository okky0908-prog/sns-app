import type { Ref } from 'react'
import styles from './FormField.module.css'

interface FormFieldProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  type?: 'text' | 'email' | 'password'
  autoComplete?: string
  autoFocus?: boolean
  maxLength?: number
  /** 入力欄の前に付ける文字（ユーザー名の「@」など） */
  prefix?: string
  /** 入力欄の下に出すエラーメッセージ */
  error?: string
  inputRef?: Ref<HTMLInputElement>
}

/** ラベル・入力欄・エラーメッセージのまとまり（ログイン・新規登録で共通） */
export function FormField({
  id,
  label,
  value,
  onChange,
  type = 'text',
  autoComplete,
  autoFocus,
  maxLength,
  prefix,
  error,
  inputRef,
}: FormFieldProps) {
  const errorId = `${id}-error`
  const input = (
    <input
      id={id}
      ref={inputRef}
      type={type}
      value={value}
      autoComplete={autoComplete}
      autoFocus={autoFocus}
      maxLength={maxLength}
      aria-invalid={error ? true : undefined}
      aria-describedby={error ? errorId : undefined}
      onChange={(e) => onChange(e.target.value)}
    />
  )
  return (
    <div className={`${styles.field} ${error ? styles.hasError : ''}`}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      {prefix ? (
        <span className={styles.withPrefix}>
          <span className={styles.prefix}>{prefix}</span>
          {input}
        </span>
      ) : (
        input
      )}
      {error && (
        <p id={errorId} className={styles.error}>
          {error}
        </p>
      )}
    </div>
  )
}
