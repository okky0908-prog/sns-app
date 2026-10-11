import { useCallback } from 'react'
import { useNavigate } from 'react-router'

/**
 * 画面の「←」（戻る）。アプリの中から来たときは前の画面へ、URL を直接開いたとき（戻る先がない）は fallback へ移動する
 *
 * @param fallback 戻る先がないときに移動する URL
 */
export function useGoBack(fallback: string) {
  const navigate = useNavigate()
  return useCallback(() => {
    if (window.history.state?.idx > 0) navigate(-1)
    else navigate(fallback)
  }, [navigate, fallback])
}
