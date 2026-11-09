import { expect, test, type Page } from '@playwright/test'
import { loginByUi, newUser, signupByApi, TINY_PNG } from '../e2e/support'

/**
 * 本番環境のスモークテスト（playwright.smoke.config.ts）。
 * 画像つきの投稿・画像の表示・アイコンのプレビュー（blob: の URL）まで操作し、
 * CSP の違反とページのエラー（JavaScript の例外）が1件もないことを確かめる。
 * 「Failed to load resource」（ログイン前に開いたときの再発行の 401 など、アプリが想定している応答）は数えない
 */
function watchErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('console', (message) => {
    // CSP の違反は「Refused to load ... because it violates the following Content Security Policy directive」と出る
    if (message.type() === 'error' && message.text().includes('Content Security Policy')) errors.push(message.text())
  })
  page.on('pageerror', (error) => errors.push(error.message))
  return errors
}

test('ログイン → 画像つき投稿 → 画像の表示 → アイコンのプレビューまで、CSP の違反もエラーもない', async ({ page, request }) => {
  const errors = watchErrors(page)
  const user = newUser('smoke')
  await signupByApi(request, user)
  await loginByUi(page, user)

  // 画像つきの投稿（選んだ画像のプレビューは blob: の URL）
  await page.getByRole('button', { name: '✏ 投稿する' }).click()
  const dialog = page.getByRole('dialog', { name: '新しい投稿' })
  await dialog.getByLabel('画像を選ぶ').setInputFiles({ name: 'dot.png', mimeType: 'image/png', buffer: TINY_PNG })
  const preview = dialog.getByRole('list', { name: '添付する画像' }).locator('img')
  await expect.poll(() => preview.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth)).toBe(1)
  await dialog.getByRole('textbox', { name: '本文' }).fill(`スモークテスト ${user.username}`)
  await dialog.getByRole('button', { name: '投稿する' }).click()
  await expect(dialog).toBeHidden()

  // 投稿の画像は、画像用の CloudFront（別のドメイン）から読み込む
  const card = page.locator('article').filter({ hasText: `スモークテスト ${user.username}` })
  const image = card.getByRole('button', { name: '画像 1 / 1 を拡大' }).locator('img')
  await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth)).toBe(1)
  expect(new URL(await image.getAttribute('src') ?? '').origin).not.toBe(new URL(page.url()).origin)

  // アイコンのプレビュー（blob: の URL）
  await page.goto('/settings/profile')
  await page.getByLabel('アイコン画像').setInputFiles({ name: 'icon.png', mimeType: 'image/png', buffer: TINY_PNG })
  const iconPreview = page.locator('img[src^="blob:"]')
  await expect.poll(() => iconPreview.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth)).toBe(1)

  expect(errors).toEqual([])
})
