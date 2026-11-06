import { expect, test } from '@playwright/test'
import { followByApi, loginByUi, newUser, postByApi, postCard, signupByApi } from './support'

// タイムライン（docs/screens.md の UC-07・UC-08・UC-19）
test.describe('タイムライン', () => {
  test('フォロー中のタイムラインは新しい順に20件ずつ出て、下までスクロールすると続きを読み込む（UC-07）', async ({
    page,
    request,
  }) => {
    const author = newUser('kate')
    const authorToken = await signupByApi(request, author)
    for (let i = 1; i <= 25; i++) {
      await postByApi(request, authorToken, `続きを読み込む投稿 ${author.username} #${i}`)
    }
    const me = newUser('leo')
    const token = await signupByApi(request, me)
    await followByApi(request, token, author.username)

    await loginByUi(page, me)
    const cards = page.getByRole('region', { name: '投稿一覧' }).locator('article')
    await expect(cards).toHaveCount(20)
    await expect(cards.first().getByText(`続きを読み込む投稿 ${author.username} #25`, { exact: true })).toBeVisible()
    await expect(page.getByText('これ以上の投稿はありません')).toBeHidden()

    await page.getByTestId('timeline-sentinel').scrollIntoViewIfNeeded()
    await expect(cards).toHaveCount(25)
    await expect(cards.last().getByText(`続きを読み込む投稿 ${author.username} #1`, { exact: true })).toBeVisible()
    await expect(page.getByText('これ以上の投稿はありません')).toBeVisible()
  })

  test('フォローしていない人の投稿は、フォロー中には出ず、全体のタブに出る（UC-08）', async ({ page, request }) => {
    const stranger = newUser('mia')
    const strangerToken = await signupByApi(request, stranger)
    const content = `フォローしていない人の投稿 ${stranger.username}`
    await postByApi(request, strangerToken, content)
    const me = newUser('noah')
    await signupByApi(request, me)

    await loginByUi(page, me)
    await expect(page.getByText('まだ投稿がありません。')).toBeVisible()
    await expect(postCard(page, content)).toHaveCount(0)

    await page.getByRole('navigation', { name: 'タイムラインの切り替え' }).getByRole('link', { name: '全体' }).click()
    await expect(page).toHaveURL('/all')
    await expect(postCard(page, content)).toBeVisible()

    // 読み込み直しても全体のタブのまま
    await page.reload()
    await expect(postCard(page, content)).toBeVisible()
  })

  test('開いたままにしていると、60秒ごとの確認で新しい投稿を知らせ、押すまで一覧は変わらない（UC-19）', async ({
    page,
    request,
  }) => {
    const author = newUser('olive')
    const authorToken = await signupByApi(request, author)
    await postByApi(request, authorToken, `前からある投稿 ${author.username}`)
    const me = newUser('paul')
    const token = await signupByApi(request, me)
    await followByApi(request, token, author.username)

    // 60秒待たずに確かめるため、ブラウザの時計を偽物にして進める（ページを開く前に入れる）
    await page.clock.install()
    await loginByUi(page, me)
    await expect(postCard(page, `前からある投稿 ${author.username}`)).toBeVisible()

    const newContent = `あとから来た投稿 ${author.username}`
    await postByApi(request, authorToken, newContent)

    await test.step('60秒たつまでは知らせない', async () => {
      await page.clock.runFor(59_000)
      await expect(page.getByRole('dialog', { name: '新しい投稿があります' })).toBeHidden()
    })

    await test.step('60秒たつと知らせる。「あとで」で閉じても、一覧はそのままで、上にボタンが残る', async () => {
      await page.clock.runFor(1_000)
      const dialog = page.getByRole('dialog', { name: '新しい投稿があります' })
      await expect(dialog).toContainText('1件の新しい投稿があります')
      await dialog.getByRole('button', { name: 'あとで' }).click()
      await expect(dialog).toBeHidden()
      await expect(postCard(page, newContent)).toHaveCount(0)
    })

    await test.step('上のボタンを押すと、最新を取り直して先頭に出る', async () => {
      await page.getByRole('button', { name: '↑ 1件の新しい投稿' }).click()
      const cards = page.getByRole('region', { name: '投稿一覧' }).locator('article')
      await expect(cards.first()).toContainText(newContent)
      await expect(page.getByRole('button', { name: '↑ 1件の新しい投稿' })).toBeHidden()
    })
  })
})
