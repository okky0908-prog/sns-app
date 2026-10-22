import { defineConfig, devices } from '@playwright/test'

/**
 * E2E テスト（npm run test:e2e。docs/test-plan.md「段階 G」）。本物のブラウザで、画面 → API → DB まで全部本物で通す。
 *
 * - バックエンドは ./gradlew bootTestRun で、使い捨てのコンテナ（PostgreSQL・LocalStack）につないで起動する（TestBackendApplication）。
 *   開発用の DB には書き込まない。止めるとデータも消える
 * - ポートは開発と同じ（バックエンド 8080・フロント 5173。CLAUDE.md）。8080 で開発用のバックエンドが動いていたら、
 *   開発用の DB に書き込まないよう、使い回さずに起動の失敗にする（止めてから実行する）
 * - テストどうしは、毎回違うユーザーを作って、データが混ざらないようにする（e2e/support.ts）
 */
export default defineConfig({
  testDir: 'e2e',
  // 大事な流れに絞って少数なので、順番に1つずつ動かす（たまに落ちることを減らす）
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    // 失敗したときに原因を追えるよう、やり直したときの操作の記録を残す
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: './gradlew bootTestRun',
      cwd: '../backend',
      url: 'http://localhost:8080/api/health',
      reuseExistingServer: false,
      // コンテナの起動とビルドを含むので長めに待つ
      timeout: 300_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev',
      url: 'http://localhost:5173',
      // フロントの開発サーバーは API を 8080 へ中継するだけなので、動いていればそのまま使う
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
})
