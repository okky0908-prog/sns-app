import { expect, test, type Page } from '@playwright/test'
import { commentByApi, loginByUi, newUser, postByApi, signupByApi, TINY_PNG } from './support'

// スマートフォンの幅（docs/non-functional-requirements.md「互換性」：スマートフォンでは表示が崩れない程度に対応する。
// docs/screens.md「共通ヘッダー」：640px 以下では、表示名の文字と「プロフィール」の文字リンクを隠して1行に収める）。
// 幅はよく使われる中で狭い 375px（iPhone SE など）
test.use({ viewport: { width: 375, height: 667 }, isMobile: true, hasTouch: true })

/** 横にはみ出して、ページが横にスクロールしない */
async function expectNoHorizontalScroll(page: Page, screen: string) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(scrollWidth, `${screen} が横にはみ出している`).toBeLessThanOrEqual(clientWidth)
}

/** ヘッダーの部品が、どれも画面の幅とヘッダーの高さに収まっている（2行に折り返したり、右にはみ出したりしない） */
async function expectHeaderFits(page: Page) {
  const banner = page.getByRole('banner')
  const header = await banner.boundingBox()
  expect(header).not.toBeNull()
  for (const item of await banner.locator('a:visible, button:visible, input:visible').all()) {
    const box = await item.boundingBox()
    const name = (await item.getAttribute('aria-label')) ?? (await item.textContent())
    expect(box, name ?? '').not.toBeNull()
    if (!box || !header) continue
    expect(box.x, `${name} が左にはみ出している`).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width, `${name} が右にはみ出している`).toBeLessThanOrEqual(375)
    expect(box.y, `${name} がヘッダーの上にはみ出している`).toBeGreaterThanOrEqual(header.y)
    expect(box.y + box.height, `${name} がヘッダーの下にはみ出している`).toBeLessThanOrEqual(header.y + header.height)
  }
}

test('スマートフォンの幅でも、主な画面が横にはみ出さず、ヘッダーが1行に収まる', async ({ page, request }) => {
  const me = newUser('sid')
  const token = await signupByApi(request, me)
  // 長い本文（改行のない英数字）と画像は、はみ出しやすい
  const post = await postByApi(request, token, `${'a'.repeat(120)} ${me.username}`, {
    name: 'dot.png',
    mimeType: 'image/png',
    buffer: TINY_PNG,
  })
  await commentByApi(request, token, post.id, 'b'.repeat(100))

  await page.goto('/login')
  await expectNoHorizontalScroll(page, 'ログイン')
  await loginByUi(page, me)

  // 表示名の文字と「プロフィール」の文字リンクは隠し、アイコンからプロフィールへ行ける
  const banner = page.getByRole('banner')
  await expect(banner.getByRole('link', { name: 'プロフィール', exact: true })).toBeHidden()
  await expect(banner.getByText(me.displayName)).toBeHidden()
  await expect(banner.getByRole('link', { name: `${me.displayName}（@${me.username}）のプロフィール` })).toBeVisible()

  const screens: [string, string, string][] = [
    ['ホーム', '/', me.username],
    ['投稿詳細', `/posts/${post.id}`, 'b'.repeat(100)],
    ['プロフィール', `/users/${me.username}`, me.username],
    ['ユーザー検索', `/search?q=${me.username}`, `@${me.username}`],
    ['プロフィール編集', '/settings/profile', 'プロフィールを編集'],
  ]
  for (const [screen, path, text] of screens) {
    await page.goto(path)
    await expect(page.getByText(text).first(), screen).toBeVisible()
    await expectNoHorizontalScroll(page, screen)
    await expectHeaderFits(page)
  }

  // 投稿のモーダルも画面に収まる
  await page.goto('/')
  await page.getByRole('button', { name: '✏ 投稿する' }).click()
  const dialog = page.getByRole('dialog', { name: '新しい投稿' })
  const box = await dialog.boundingBox()
  expect(box && box.x >= 0 && box.x + box.width <= 375).toBe(true)
  await expect(dialog.getByRole('button', { name: '投稿する' })).toBeInViewport()
})
