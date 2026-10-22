import { expect, test } from '@playwright/test'
import { newUser } from './support'

// 主要な流れ：新規登録 → 投稿 → いいね → コメント（docs/test-plan.md「4. ケースの設計例」）
test('新規登録して投稿し、いいね・コメントでき、読み込み直しても残っている', async ({ page }) => {
  const user = newUser('alice')

  await test.step('新規登録すると、ログインした状態でホームが開く', async () => {
    await page.goto('/signup')
    await page.getByLabel('ユーザー名').fill(user.username)
    await page.getByLabel('表示名').fill(user.displayName)
    await page.getByLabel('メールアドレス').fill(user.email)
    await page.getByLabel('パスワード（8文字以上）').fill(user.password)
    await page.getByLabel('パスワード（確認）').fill(user.password)
    await page.getByRole('button', { name: '登録する' }).click()

    await expect(page).toHaveURL('/')
    await expect(page.getByRole('link', { name: `${user.displayName}（@${user.username}）のプロフィール` })).toBeVisible()
  })

  const content = `E2E の投稿です ${user.username}`
  const card = page.locator('article').filter({ hasText: content })

  await test.step('投稿すると、タイムラインの先頭に出る', async () => {
    await page.getByRole('button', { name: '✏ 投稿する' }).click()
    const dialog = page.getByRole('dialog', { name: '新しい投稿' })
    await dialog.getByRole('textbox', { name: '本文' }).fill(content)
    await dialog.getByRole('button', { name: '投稿する' }).click()

    await expect(dialog).toBeHidden()
    await expect(page.getByRole('status')).toHaveText('投稿しました')
    await expect(card).toBeVisible()
  })

  await test.step('いいねすると数が増え、読み込み直しても残っている', async () => {
    await card.getByRole('button', { name: 'いいね' }).click()
    await expect(card.getByRole('button', { name: 'いいねを取り消す' })).toHaveText('♥1')

    await page.reload()
    await expect(card.getByRole('button', { name: 'いいねを取り消す' })).toHaveText('♥1')
  })

  await test.step('投稿詳細でコメントすると、一覧とコメント数に反映され、読み込み直しても残っている', async () => {
    await card.getByRole('link', { name: 'コメント（0件）' }).click()
    await expect(page).toHaveURL(/\/posts\/\d+$/)
    await expect(page.getByRole('textbox', { name: 'コメント' })).toBeFocused() // 💬 から来たら入力欄へ

    await page.getByRole('textbox', { name: 'コメント' }).fill('E2E のコメントです')
    await page.getByRole('button', { name: 'コメントする' }).click()

    const comments = page.getByRole('region', { name: 'コメント' })
    await expect(comments.getByText('E2E のコメントです')).toBeVisible()
    await expect(page.getByRole('link', { name: 'コメント（1件）' })).toBeVisible()

    await page.reload()
    await expect(comments.getByText('E2E のコメントです')).toBeVisible()
    await expect(page.getByRole('link', { name: 'コメント（1件）' })).toBeVisible()
  })
})
