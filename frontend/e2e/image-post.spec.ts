import { expect, test } from '@playwright/test'
import { loginByUi, newUser, signupByApi, TINY_PNG } from './support'

// 画像は S3（ここでは LocalStack）に保存し、ブラウザはその URL から直接読み込む。
// 部品のテストでは確かめられない「保存 → 配信」までをここで確かめる
test('画像付きで投稿すると、保存された画像がタイムラインに表示される', async ({ page, request }) => {
  const user = newUser('erin')
  await signupByApi(request, user)
  await loginByUi(page, user)

  await page.getByRole('button', { name: '✏ 投稿する' }).click()
  const dialog = page.getByRole('dialog', { name: '新しい投稿' })
  await dialog.getByLabel('画像を選ぶ').setInputFiles({ name: 'dot.png', mimeType: 'image/png', buffer: TINY_PNG })
  await expect(dialog.getByRole('list', { name: '添付する画像' }).getByRole('listitem')).toHaveCount(1)
  await dialog.getByRole('textbox', { name: '本文' }).fill(`画像の投稿 ${user.username}`)
  await dialog.getByRole('button', { name: '投稿する' }).click()
  await expect(dialog).toBeHidden()

  const card = page.locator('article').filter({ hasText: `画像の投稿 ${user.username}` })
  const image = card.getByRole('button', { name: '画像 1 / 1 を拡大' }).locator('img')
  await expect(image).toBeVisible()
  // 壊れた画像でなく、実際に読み込めている（幅が 0 でない）
  await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth)).toBe(1)

  // 押すと拡大表示する
  await card.getByRole('button', { name: '画像 1 / 1 を拡大' }).click()
  await expect(page.getByRole('dialog', { name: '画像 1 / 1' })).toBeVisible()
})
