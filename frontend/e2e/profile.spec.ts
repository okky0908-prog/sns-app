import { expect, test } from '@playwright/test'
import { loginByUi, newUser, postByApi, postCard, signupByApi, TINY_PNG } from './support'

// プロフィール（docs/screens.md の UC-16・UC-17）
test.describe('プロフィール', () => {
  test('ヘッダーの「プロフィール」から、自分のプロフィールと投稿を見られる（UC-16）', async ({ page, request }) => {
    const me = newUser('amy')
    const token = await signupByApi(request, me)
    await postByApi(request, token, `自分の投稿 ${me.username}`)

    await loginByUi(page, me)
    await page.getByRole('banner').getByRole('link', { name: 'プロフィール', exact: true }).click()

    await expect(page).toHaveURL(`/users/${me.username}`)
    await expect(page.getByRole('heading', { name: me.displayName })).toBeVisible()
    const profile = page.getByRole('region', { name: 'プロフィール' })
    await expect(profile.getByRole('link', { name: 'プロフィールを編集' })).toBeVisible()
    // 自分にはフォローのボタンを出さない
    await expect(profile.getByRole('button', { name: 'フォローする' })).toHaveCount(0)
    await expect(postCard(page, `自分の投稿 ${me.username}`)).toBeVisible()
  })

  test('プロフィールを編集すると、プロフィール・ヘッダー・過去の投稿の表示名とアイコンが新しくなる（UC-17）', async ({
    page,
    request,
  }) => {
    const me = newUser('ben')
    const token = await signupByApi(request, me)
    const content = `編集する前からある投稿 ${me.username}`
    await postByApi(request, token, content)
    const newName = `新しい名前 ${me.username.slice(-4)}`

    await loginByUi(page, me)
    await page.goto(`/users/${me.username}`)
    await page.getByRole('link', { name: 'プロフィールを編集' }).click()
    await expect(page).toHaveURL('/settings/profile')

    await page.getByLabel('表示名').fill(newName)
    await page.getByLabel('自己紹介').fill('E2E で書いた自己紹介です')
    await page.getByLabel('アイコン画像').setInputFiles({ name: 'icon.png', mimeType: 'image/png', buffer: TINY_PNG })
    await page.getByRole('button', { name: '保存する' }).click()

    await expect(page).toHaveURL(`/users/${me.username}`)
    await expect(page.getByRole('status')).toHaveText('プロフィールを更新しました')

    const check = async () => {
      const profile = page.getByRole('region', { name: 'プロフィール' })
      await expect(profile).toContainText(newName)
      await expect(profile).toContainText('E2E で書いた自己紹介です')
      await expect(
        page.getByRole('banner').getByRole('link', { name: `${newName}（@${me.username}）のプロフィール` }),
      ).toBeVisible()
      // 過去の投稿のカードも、新しい表示名とアイコン（実際に読み込めている画像）になる
      const card = postCard(page, content)
      await expect(card.getByRole('link', { name: newName, exact: true })).toBeVisible()
      const icon = card.locator('img').first()
      await expect.poll(() => icon.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth)).toBe(1)
    }
    await check()
    await page.reload()
    await check()
  })

  test('変更したまま「キャンセル」すると確認が出て、破棄すると保存されない', async ({ page, request }) => {
    const me = newUser('cara')
    await signupByApi(request, me)

    await loginByUi(page, me)
    await page.goto('/settings/profile')
    await page.getByLabel('表示名').fill('保存しない名前')
    await page.getByRole('button', { name: 'キャンセル' }).click()
    await page.getByRole('alertdialog', { name: '変更は保存されません。よろしいですか？' }).getByRole('button', { name: '破棄する' }).click()

    await expect(page).toHaveURL(`/users/${me.username}`)
    await expect(page.getByRole('heading', { name: me.displayName })).toBeVisible()
    await page.goto('/settings/profile')
    await expect(page.getByLabel('表示名')).toHaveValue(me.displayName)
  })
})
