import { expect, test } from '@playwright/test'
import { loginByUi, newUser, signupByApi } from './support'

// ログイン状態は、アクセストークン（画面の中だけ）とリフレッシュトークン（HttpOnly の Cookie）で保つ。
// 部品のテストでは通信を偽物にしているので、Cookie を含めた本物のやり取りはここで確かめる
test.describe('ログイン・ログアウト', () => {
  test('ログインしたら、読み込み直しても・新しいタブで開いてもログインしたまま。ログアウトしたら、ログインが必要な画面に入れない', async ({
    page,
    request,
  }) => {
    const user = newUser('bob')
    await signupByApi(request, user)

    await loginByUi(page, user)

    await page.reload()
    await expect(page.getByRole('heading', { name: 'ホーム' })).toBeVisible()

    const another = await page.context().newPage()
    await another.goto('/settings/profile')
    await expect(another.getByLabel('表示名')).toHaveValue(user.displayName)
    await another.close()

    await page.getByRole('button', { name: 'ログアウト' }).click()
    await expect(page).toHaveURL('/login')

    await page.goto('/')
    await expect(page).toHaveURL('/login')
    await page.reload()
    await expect(page).toHaveURL('/login')
  })

  test('パスワードが違えばログインできず、パスワード欄だけ空になる', async ({ page, request }) => {
    const user = newUser('carol')
    await signupByApi(request, user)

    await page.goto('/login')
    await page.getByLabel('メールアドレス').fill(user.email)
    await page.getByLabel('パスワード', { exact: true }).fill('wrong-password')
    await page.getByRole('button', { name: 'ログイン' }).click()

    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByLabel('メールアドレス')).toHaveValue(user.email)
    await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue('')
    await expect(page).toHaveURL('/login')
  })

  test('使われているユーザー名で登録しようとしたら、その項目の下にエラーを出す', async ({ page, request }) => {
    const existing = newUser('dave')
    await signupByApi(request, existing)
    const user = newUser('dave')

    await page.goto('/signup')
    await page.getByLabel('ユーザー名').fill(existing.username)
    await page.getByLabel('表示名').fill(user.displayName)
    await page.getByLabel('メールアドレス').fill(user.email)
    await page.getByLabel('パスワード（8文字以上）').fill(user.password)
    await page.getByLabel('パスワード（確認）').fill(user.password)
    await page.getByRole('button', { name: '登録する' }).click()

    await expect(page.getByLabel('ユーザー名')).toHaveAttribute('aria-invalid', 'true')
    await expect(page).toHaveURL('/signup')
  })
})
