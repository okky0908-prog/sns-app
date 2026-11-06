import { expect, test } from '@playwright/test'
import { loginByUi, newUser, postByApi, signupByApi } from './support'

// 画面の決まり（docs/screens.md「画面一覧」の下の注意書き、非機能要件「ユーザビリティ」：
// 画面ごとに URL を分け、読み込み直し・戻る・URL の共有で同じ画面が出る）
test.describe('画面の移動', () => {
  // ログインが必要な画面（docs/screens.md の S-03〜S-10）
  const protectedPaths = ['/', '/all', '/posts/1', '/users/someone', '/users/someone/followers', '/settings/profile', '/search']

  test('ログインしていなければ、ログインが必要な画面はどれもログイン画面に移る', async ({ page }) => {
    for (const path of protectedPaths) {
      await page.goto(path)
      await expect(page, path).toHaveURL('/login')
    }
  })

  test('ログイン済みでログイン・新規登録の画面を開くとホームに、ない URL を開くとホームに移る', async ({
    page,
    request,
  }) => {
    const me = newUser('dan')
    await signupByApi(request, me)
    await loginByUi(page, me)

    for (const path of ['/login', '/signup', '/no-such-page']) {
      await page.goto(path)
      await expect(page, path).toHaveURL('/')
      await expect(page.getByRole('heading', { name: 'ホーム' })).toBeVisible()
    }
  })

  test('ブラウザの戻る・進むで、前に見ていた画面に戻る', async ({ page, request }) => {
    const author = newUser('eve')
    const authorToken = await signupByApi(request, author)
    const content = `戻る・進むで見る投稿 ${author.username}`
    const post = await postByApi(request, authorToken, content)
    const me = newUser('fay')
    await signupByApi(request, me)
    await loginByUi(page, me)

    await page.goto(`/users/${author.username}`)
    await page.getByRole('link', { name: 'コメント（0件）' }).click()
    await expect(page).toHaveURL(`/posts/${post.id}`)

    await page.goBack()
    await expect(page).toHaveURL(`/users/${author.username}`)
    await expect(page.getByRole('heading', { name: author.displayName })).toBeVisible()

    await page.goForward()
    await expect(page).toHaveURL(`/posts/${post.id}`)
    await expect(page.getByText(content)).toBeVisible()
  })

  test('検索の結果は URL に残り、読み込み直し・同じ URL を別のタブで開いても同じ結果が出る', async ({
    page,
    request,
  }) => {
    const target = newUser('gil')
    await signupByApi(request, target)
    const me = newUser('hal')
    await signupByApi(request, me)
    await loginByUi(page, me)

    await page.getByRole('searchbox', { name: 'ユーザーを検索' }).fill(target.username)
    await page.getByRole('searchbox', { name: 'ユーザーを検索' }).press('Enter')
    await expect(page).toHaveURL(`/search?q=${target.username}`)
    const result = page.getByRole('region', { name: `「${target.username}」の検索結果` })
    await expect(result.getByRole('listitem')).toHaveCount(1)

    await page.reload()
    await expect(result.getByRole('listitem')).toHaveCount(1)

    const another = await page.context().newPage()
    await another.goto(page.url())
    await expect(
      another.getByRole('region', { name: `「${target.username}」の検索結果` }).getByRole('listitem'),
    ).toContainText(`@${target.username}`)
    await another.close()
  })

  test('検索のキーワードの % は「何でも」ではなく、ふつうの文字として探す', async ({ page, request }) => {
    const me = newUser('ivy')
    await signupByApi(request, me)
    await loginByUi(page, me)

    // % をそのまま SQL の LIKE に渡すと、全員が当てはまってしまう（docs/database.md「ユーザー検索の SQL」）
    await page.goto('/search?q=%25')
    await expect(page.getByText('「%」に一致するユーザーは見つかりませんでした')).toBeVisible()
  })
})
