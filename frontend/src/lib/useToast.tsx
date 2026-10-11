import { useCallback, useState } from 'react'
import { Toast } from '../components/Toast'

/**
 * 画面下に短く出すお知らせ（トースト）。notify で出し、toastElement を画面の中に置く。
 * 同じ文言を続けて出しても出し直されるよう、出すたびに番号（key）を変える
 */
export function useToast() {
  const [toast, setToast] = useState<{ id: number; message: string; error: boolean } | null>(null)

  const notify = useCallback(
    (message: string, error = false) => setToast((prev) => ({ id: (prev?.id ?? 0) + 1, message, error })),
    [],
  )
  const clearToast = useCallback(() => setToast(null), [])

  const toastElement = toast && <Toast key={toast.id} message={toast.message} error={toast.error} onDone={clearToast} />
  return { notify, toastElement }
}
