import { expect, test } from '@playwright/test'

// 画面で起きたエラーが、ログインしていなくてもサーバーに届く（docs/observability.md「3.1 ログの種類」の画面のエラー）。
// 部品のテストでは送る先を偽物にしているので、サーバーが受け付ける（入力チェックを通る）ところまでをここで確かめる
test('ログイン画面で捕まえ損ねた例外が起きたら、サーバーに送られて受け付けられる', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'SNSアプリにログイン' })).toBeVisible()

  const responsePromise = page.waitForResponse('**/api/client-errors')
  await page.evaluate(() => {
    setTimeout(() => {
      throw new Error('E2E で起こしたエラー')
    })
  })
  const response = await responsePromise

  expect(response.status()).toBe(204)
  expect(response.request().postDataJSON()).toMatchObject({
    kind: 'error',
    message: 'E2E で起こしたエラー',
    path: '/login',
  })
})
