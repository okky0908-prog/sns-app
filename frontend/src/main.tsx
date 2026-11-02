import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import { AuthProvider } from './auth/AuthProvider'
import { installGlobalErrorHandlers, reportError } from './lib/errorReporting'
import { router } from './router'
import './index.css'

// 捕まえ損ねた例外・Promise の失敗をサーバーに送る（docs/observability.md「3.1 ログの種類」の画面のエラー）
installGlobalErrorHandlers(reportError)

createRoot(document.getElementById('root')!, {
  // 画面の切り替え（router）の外で起きた表示中のエラー（AuthProvider など）。画面の中のものは ErrorPage が受け止めて送る
  onUncaughtError: (error, info) => {
    reportError('render', error, info.componentStack)
    console.error(error)
  },
}).render(
  <StrictMode>
    <AuthProvider>
      <RouterProvider router={router} />
    </AuthProvider>
  </StrictMode>,
)
