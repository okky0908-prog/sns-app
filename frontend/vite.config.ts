/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  // テスト（Vitest。npm test）の設定
  test: {
    // 日付の表示（2026/09/30 など）は実行する場所のタイムゾーンで変わるので、利用者と同じ日本時間に固定する
    // （CI は UTC で動くため、固定しないと手元と結果がずれる）
    env: { TZ: 'Asia/Tokyo' },
  },
})
