import { defineConfig, devices } from '@playwright/test'

/**
 * 本番環境のスモークテスト（SMOKE_URL=https://xxxx.cloudfront.net/ npm run test:smoke。docs/infrastructure.md「デプロイ」）。
 *
 * - デプロイした環境（CloudFront）を本物のブラウザで操作し、CSP（security_headers.tf）で画面が壊れていないことを確かめる。
 *   CSP は CloudFront が付けるので、手元の E2E テスト（vite の開発サーバー）では確かめられない
 * - サーバーは起動しない。確かめる環境の URL を SMOKE_URL で渡す。CI では動かさない（型チェックだけ CI で行う）
 * - 本番の DB にテスト用のユーザーと投稿が残る（ユーザー名は smoke_ で始まる）
 */
const baseURL = process.env.SMOKE_URL
if (!baseURL) {
  throw new Error('確かめる環境の URL を SMOKE_URL で指定してください（例: SMOKE_URL=https://xxxx.cloudfront.net/）')
}

export default defineConfig({
  testDir: 'smoke',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  outputDir: 'smoke-results',
  use: {
    baseURL,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
