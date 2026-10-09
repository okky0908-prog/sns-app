import styles from './Avatar.module.css'

interface AvatarProps {
  displayName: string
  /** アイコン画像の URL。null なら表示名の頭文字を丸の中に出す */
  iconUrl: string | null
  /** 直径（px） */
  size: number
}

/**
 * ユーザーのアイコン（docs/feature-specs/07_profile.md「アイコンの表示」）。画像は丸く切り抜いて表示し、画像そのものは加工しない。
 * 読み上げでは、まわりの名前やリンクで誰かがわかるので、アイコン自体は読み上げない
 */
export function Avatar({ displayName, iconUrl, size }: AvatarProps) {
  const style = { width: size, height: size, fontSize: Math.round(size * 0.4) }
  if (iconUrl) {
    return <img className={styles.avatar} src={iconUrl} alt="" width={size} height={size} style={style} />
  }
  return (
    <span className={`${styles.avatar} ${styles.initial}`} aria-hidden="true" style={style}>
      {[...displayName][0] ?? '?'}
    </span>
  )
}
