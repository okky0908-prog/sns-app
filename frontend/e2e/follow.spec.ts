import { expect, test, type Page } from '@playwright/test'
import { commentByApi, followByApi, loginByUi, newUser, postByApi, postCard, signupByApi } from './support'

// フォロー（docs/screens.md の UC-12〜UC-15）

/** プロフィールでフォローし、フォロワー数が増えたことを確かめる */
async function followOnProfile(page: Page, username: string) {
  await expect(page).toHaveURL(`/users/${username}`)
  await page.getByRole('region', { name: 'プロフィール' }).getByRole('button', { name: 'フォローする' }).click()
  await expect(page.getByRole('link', { name: '1 フォロワー' })).toBeVisible()
}

test('ユーザーを検索してフォローすると、その人の投稿がフォロー中のタイムラインに出る（UC-12）', async ({
  page,
  request,
}) => {
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
  await page.getByRole('link', { name: author.displayName, exact: true }).click()

  await followOnProfile(page, author.username)

  await page.getByRole('link', { name: 'ホーム' }).first().click()
  await expect(page.getByText(`フォローされる人の投稿 ${author.username}`)).toBeVisible()

  // 読み込み直しても、フォローは残っている
  await page.goto(`/users/${author.username}`)
  await expect(page.getByRole('button', { name: /フォロー中/ })).toHaveAttribute('aria-pressed', 'true')
})

test('投稿の投稿者名、コメントした人の名前からプロフィールを開いてフォローできる（UC-13）', async ({ page, request }) => {
  const author = newUser('sam')
  const authorToken = await signupByApi(request, author)
  const content = `名前から開かれる投稿 ${author.username}`
  const post = await postByApi(request, authorToken, content)
  const commenter = newUser('tina')
  const commenterToken = await signupByApi(request, commenter)
  await commentByApi(request, commenterToken, post.id, `名前から開かれるコメント ${commenter.username}`)
  const me = newUser('uma')
  await signupByApi(request, me)
  await loginByUi(page, me)

  await test.step('全体のタイムラインの投稿者名から', async () => {
    await page.goto('/all')
    await postCard(page, content).getByRole('link', { name: author.displayName, exact: true }).click()
    await followOnProfile(page, author.username)
  })

  await test.step('投稿詳細のコメントした人の名前から', async () => {
    await page.goto(`/posts/${post.id}`)
    await page
      .getByRole('region', { name: 'コメント' })
      .getByRole('link', { name: commenter.displayName, exact: true })
      .click()
    await followOnProfile(page, commenter.username)
  })
})

test('フォロー中・フォロワーの一覧から、その人をフォローできる（UC-14）', async ({ page, request }) => {
  const friend = newUser('vera')
  const friendToken = await signupByApi(request, friend)
  const friendOfFriend = newUser('walt')
  await signupByApi(request, friendOfFriend)
  await followByApi(request, friendToken, friendOfFriend.username)
  const me = newUser('xena')
  await signupByApi(request, me)

  await loginByUi(page, me)
  await page.goto(`/users/${friend.username}`)
  await page.getByRole('link', { name: '1 フォロー中' }).click()
  await expect(page).toHaveURL(`/users/${friend.username}/following`)

  const row = page.getByRole('region', { name: 'フォロー中' }).getByRole('listitem').filter({ hasText: `@${friendOfFriend.username}` })
  await row.getByRole('button', { name: 'フォローする' }).click()
  await expect(row.getByRole('button', { name: /フォロー中/ })).toHaveAttribute('aria-pressed', 'true')

  // 読み込み直しても残っていて、相手のフォロワー数も増えている（もとからフォローしていた friend と、自分の2人）
  await page.reload()
  await expect(row.getByRole('button', { name: /フォロー中/ })).toHaveAttribute('aria-pressed', 'true')
  await page.goto(`/users/${friendOfFriend.username}`)
  await expect(page.getByRole('link', { name: '2 フォロワー' })).toBeVisible()
})

test('フォローを解除すると、フォロワー数が減り、その人の投稿がフォロー中のタイムラインに出なくなる（UC-15）', async ({
  page,
  request,
}) => {
  const author = newUser('yuri')
  const authorToken = await signupByApi(request, author)
  const content = `解除される人の投稿 ${author.username}`
  await postByApi(request, authorToken, content)
  const me = newUser('zoe')
  const token = await signupByApi(request, me)
  await followByApi(request, token, author.username)

  await loginByUi(page, me)
  await expect(postCard(page, content)).toBeVisible()

  await page.goto(`/users/${author.username}`)
  // マウスを乗せるとボタンの名前が「フォロー解除」に変わるので、名前ではなく押されている状態で探す
  const button = page.getByRole('region', { name: 'プロフィール' }).getByRole('button', { pressed: true })
  await expect(button).toHaveText(/フォロー中/)
  await button.hover()
  await expect(button).toContainText('フォロー解除')
  await button.click()
  await expect(page.getByRole('button', { name: 'フォローする' })).toBeVisible()
  await expect(page.getByRole('link', { name: '0 フォロワー' })).toBeVisible()

  await page.getByRole('link', { name: 'ホーム' }).first().click()
  await expect(page.getByText('まだ投稿がありません。')).toBeVisible()
  await expect(postCard(page, content)).toHaveCount(0)
})
