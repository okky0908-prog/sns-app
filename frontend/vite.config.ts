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
    // jest-dom の確認方法と、テストのたびの片付け（src/test/setup.ts）
    setupFiles: ['src/test/setup.ts'],
    // E2E テスト（e2e/。Playwright で動かす）は含めない
    include: ['src/**/*.test.{ts,tsx}'],
    // カバレッジ（npm run test:coverage。docs/test-plan.md「ホワイトボックステスト」）。
    // テストのないファイルも 0% として数えるよう、src の下をすべて対象にする
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      // テストそのもの・自動生成した API の型・型だけのファイル・起動の入り口は数えない
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/api/schema.ts', 'src/api/types.ts', 'src/main.tsx', 'src/**/*.d.ts'],
      reporter: ['text-summary', 'html', 'json-summary'],
    },
  },
})
