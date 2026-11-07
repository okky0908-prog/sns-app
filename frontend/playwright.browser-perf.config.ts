import { defineConfig, devices } from '@playwright/test'

/**
 * ブラウザのパフォーマンステスト（npm run test:browser-perf。docs/performance-test-plan.md「10」）。
 *
 * - 画面は本番用にビルドして（vite build）、vite preview で配る。開発用のサーバーは最適化していないので、遅く出てしまう。
 *   ポートは開発と同じ 5173（CLAUDE.md）。/api はバックエンド（8080）へ中継する（vite.config.ts の server.proxy を使う）
 * - バックエンドは自動では起動しない。性能テストの DB（sns_perf）につないで、先に起動しておく（計画書の手順）
 * - 時間を測るので、1 つずつ順番に動かし、やり直しはしない。CI では動かさない（型チェックだけ CI で行う）
 */
export default defineConfig({
  testDir: 'browser-perf',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 5 * 60_000,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'browser-perf-report' }]],
  outputDir: 'browser-perf-results/test-results',
  use: {
    baseURL: 'http://localhost:5173',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run build && npx vite preview --port 5173 --strictPort',
    url: 'http://localhost:5173',
    // 開発用のサーバー（npm run dev）が動いていたら、それを測ってしまわないよう、使い回さずに起動の失敗にする
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
