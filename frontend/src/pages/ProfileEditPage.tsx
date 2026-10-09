import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, errorMessage, fetchProfile, updateProfile } from '../api/client'
import type { Profile } from '../api/types'
import { useAuth } from '../auth/authContext'
import { Avatar } from '../components/Avatar'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { FormField } from '../components/FormField'
import { countChars } from '../lib/format'
import styles from './ProfileEditPage.module.css'

const DISPLAY_NAME_MAX = 50
const BIO_MAX = 160
const ICON_MAX_BYTES = 5 * 1024 * 1024
const ICON_TYPES = ['image/jpeg', 'image/png', 'image/gif']

type Errors = Partial<Record<'displayName' | 'bio' | 'icon', string>>

/** 画面側の入力チェック（サーバーと同じ決まり。最終的な判定はサーバーが行う） */
function validate(displayName: string, bio: string): Errors {
  const errors: Errors = {}
  const nameLength = countChars(displayName.trim())
  if (nameLength < 1 || nameLength > DISPLAY_NAME_MAX) errors.displayName = '表示名は1〜50文字で入力してください'
  if (countChars(bio.trim()) > BIO_MAX) errors.bio = '自己紹介は160文字以内で入力してください'
  return errors
}

/** S-08 プロフィール編集（/settings/profile。docs/feature-specs/07_profile.md）。ログイン中のユーザー自身のプロフィールだけを編集する */
export function ProfileEditPage() {
  const { user, updateUser } = useAuth()
  const navigate = useNavigate()
  const [original, setOriginal] = useState<Profile | null>(null)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [displayName, setDisplayName] = useState('')
  const [bio, setBio] = useState('')
  const [icon, setIcon] = useState<File | null>(null)
  const [errors, setErrors] = useState<Errors>({})
  const [alert, setAlert] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  const username = user?.username ?? ''
  const profilePath = `/users/${encodeURIComponent(username)}`

  // 開いたときに、今の表示名・自己紹介・アイコンを入れる
  useEffect(() => {
    if (!username) return
    let cancelled = false
    fetchProfile(username)
      .then((res) => {
        if (cancelled) return
        setOriginal(res)
        setDisplayName(res.displayName)
        setBio(res.bio ?? '')
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setStatus('error')
      })
    return () => {
      cancelled = true
    }
  }, [username])

  // 選んだ画像のプレビュー用の URL。別の画像を選んだときと画面を閉じたときに解放する
  const preview = useMemo(() => (icon ? URL.createObjectURL(icon) : null), [icon])
  useEffect(() => {
    if (!preview) return
    return () => URL.revokeObjectURL(preview)
  }, [preview])

  const dirty =
    original !== null && (displayName !== original.displayName || bio !== (original.bio ?? '') || icon !== null)

  function handleIconChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = '' // 同じファイルを選び直しても change が起きるように
    if (!file) return
    // 形式の最終的な判定はサーバーがファイルの中身で行う。ここでは明らかに違うものを先に止める
    if (!ICON_TYPES.includes(file.type)) {
      setErrors((prev) => ({ ...prev, icon: 'jpg・png・gif の画像を選択してください' }))
      return
    }
    if (file.size > ICON_MAX_BYTES) {
      setErrors((prev) => ({ ...prev, icon: '5MB以下の画像を選択してください' }))
      return
    }
    setErrors((prev) => ({ ...prev, icon: undefined }))
    setIcon(file)
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    // 選べなかった画像（形式・大きさの違反）は保存の対象になっていないので、そのエラーでは止めない（消しておく）
    const nextErrors = validate(displayName, bio)
    setErrors(nextErrors)
    setAlert(null)
    if (nextErrors.displayName || nextErrors.bio) return

    setSubmitting(true)
    try {
      const updated = await updateProfile({ displayName: displayName.trim(), bio: bio.trim(), icon })
      // ヘッダーなど、ログイン中のユーザーの表示名・アイコンも新しいものにする
      updateUser({ id: updated.id, username: updated.username, displayName: updated.displayName, iconUrl: updated.iconUrl })
      navigate(profilePath, { replace: true, state: { notice: 'プロフィールを更新しました' } })
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors.length > 0) {
        const serverErrors: Errors = {}
        for (const { field, message } of err.fieldErrors) {
          if (field === 'displayName' || field === 'bio' || field === 'icon') serverErrors[field] ??= message
        }
        setErrors(serverErrors)
      } else {
        // アイコンのアップロード失敗（IMAGE_UPLOAD_FAILED）など。入力内容は残す
        setAlert(errorMessage(err))
      }
      setSubmitting(false)
    }
  }

  function handleCancel() {
    if (dirty) setConfirmingCancel(true)
    else navigate(profilePath)
  }

  if (status === 'loading') return <p className={styles.message}>読み込み中…</p>
  if (status === 'error' || !original)
    return <p className={styles.message}>プロフィールを読み込めませんでした。時間をおいてもう一度お試しください</p>

  const bioLength = countChars(bio.trim())
  return (
    <>
      <div className={styles.header}>
        <h1 className={styles.title}>プロフィールを編集</h1>
      </div>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        {alert && (
          <p className={styles.alert} role="alert">
            {alert}
          </p>
        )}

        <div className={styles.iconField}>
          <Avatar displayName={displayName || original.displayName} iconUrl={preview ?? original.iconUrl} size={88} />
          <div>
            <button type="button" className={styles.secondary} onClick={() => fileRef.current?.click()}>
              アイコン画像を変更
            </button>
            <input
              ref={fileRef}
              type="file"
              accept="image/jpeg,image/png,image/gif"
              className={styles.fileInput}
              aria-label="アイコン画像"
              onChange={handleIconChange}
            />
            <p className={styles.hint}>jpg・png・gif、5MBまで。保存するまでは反映されません</p>
            {errors.icon && <p className={styles.error}>{errors.icon}</p>}
          </div>
        </div>

        <FormField
          id="profile-displayName"
          label="表示名"
          autoComplete="nickname"
          value={displayName}
          onChange={setDisplayName}
          error={errors.displayName}
        />

        <div className={styles.field}>
          <label htmlFor="profile-bio" className={styles.label}>
            自己紹介
          </label>
          <textarea
            id="profile-bio"
            className={`${styles.textarea} ${errors.bio ? styles.textareaError : ''}`}
            rows={4}
            value={bio}
            aria-invalid={errors.bio ? true : undefined}
            onChange={(e) => setBio(e.target.value)}
          />
          <div className={styles.bioFooter}>
            {errors.bio ? <p className={styles.error}>{errors.bio}</p> : <span />}
            <span className={`${styles.counter} ${bioLength > BIO_MAX ? styles.over : ''}`} aria-live="polite">
              {bioLength} / {BIO_MAX}
            </span>
          </div>
        </div>

        <p className={styles.hint}>@{original.username}（ユーザー名とメールアドレスは変更できません）</p>

        <div className={styles.buttons}>
          <button type="button" className={styles.secondary} onClick={handleCancel} disabled={submitting}>
            キャンセル
          </button>
          <button type="submit" className={styles.primary} disabled={submitting}>
            {submitting ? '保存中…' : '保存する'}
          </button>
        </div>
      </form>

      {confirmingCancel && (
        <ConfirmDialog
          message="変更は保存されません。よろしいですか？"
          confirmLabel="破棄する"
          onConfirm={() => navigate(profilePath)}
          onCancel={() => setConfirmingCancel(false)}
        />
      )}
    </>
  )
}
