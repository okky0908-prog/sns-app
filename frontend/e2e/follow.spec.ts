import { expect, test } from '@playwright/test'
import { loginByUi, newUser, postByApi, signupByApi } from './support'

test('ユーザーを検索してフォローすると、その人の投稿がフォロー中のタイムラインに出る', async ({ page, request }) => {
  const author = newUser('frank')
  const authorToken = await signupByApi(request, author)
  await postByApi(request, authorToken, `フォローされる人の投稿 ${author.username}`)
  const me = newUser('grace')
  await signupByApi(request, me)

  await loginByUi(page, me)
  // まだ誰もフォローしていないので、フォロー中のタブには出ない
  await expect(page.getByText('まだ投稿がありません。')).toBeVisible()

  await page.getByRole('searchbox', { name: 'ユーザーを検索' }).fill(author.username)
  await page.getByRole('searchbox', { name: 'ユーザーを検索' }).press('Enter')
  await expect(page.getByRole('heading', { name: `「${author.username}」の検索結果` })).toBeVisible()
  await page.getByRole('link', { name: author.displayName }).click()

  await expect(page).toHaveURL(`/users/${author.username}`)
  await page.getByRole('button', { name: 'フォローする' }).click()
  await expect(page.getByRole('link', { name: '1 フォロワー' })).toBeVisible()

  await page.getByRole('link', { name: 'ホーム' }).first().click()
  await expect(page.getByText(`フォローされる人の投稿 ${author.username}`)).toBeVisible()

  // 読み込み直しても、フォローは残っている
  await page.goto(`/users/${author.username}`)
  await expect(page.getByRole('button', { name: /フォロー中/ })).toHaveAttribute('aria-pressed', 'true')
})
