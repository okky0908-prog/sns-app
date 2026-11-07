import { expect, test } from '@playwright/test'
import { commentByApi, loginByUi, newUser, postByApi, postCard, signupByApi } from './support'

// セキュリティ（docs/non-functional-requirements.md「セキュリティ」）。
// 部品のテストは画面の中だけ・サーバーのテストは API だけを見るので、本物のブラウザとサーバーをつないで初めて分かることをここで確かめる
test.describe('セキュリティ', () => {
  test('投稿に HTML や script を書いても、文字として表示され、動かない（XSS）', async ({ page, request }) => {
    const author = newUser('kim')
    const token = await signupByApi(request, author)
    const attack = `<img src=x onerror="window.xssFired='img'"><script>window.xssFired='script'</script> ${author.username}`
    await postByApi(request, token, attack)
    const dialogs: string[] = []
    page.on('dialog', (dialog) => {
      dialogs.push(dialog.message())
      void dialog.dismiss()
    })

    await loginByUi(page, author)
    const card = postCard(page, author.username)
    await expect(card).toContainText('<img src=x onerror=')
    await expect(card).toContainText('<script>')
    // 書いた要素は作られず、スクリプトも動いていない
    await expect(card.locator('img[src="x"], script')).toHaveCount(0)
    expect(await page.evaluate(() => (window as unknown as { xssFired?: string }).xssFired)).toBeUndefined()
    expect(dialogs).toEqual([])
  })

  test('アクセストークンはブラウザの保存領域に置かず、リフレッシュトークンの Cookie は JavaScript から読めない', async ({
    page,
    request,
    context,
  }) => {
    const me = newUser('lia')
    await signupByApi(request, me)
    await loginByUi(page, me)
    await page.reload()
    await expect(page.getByRole('heading', { name: 'ホーム' })).toBeVisible()

    // JWT は「eyJ」で始まる。localStorage・sessionStorage のどこにも入っていない
    const stored = await page.evaluate(() =>
      [localStorage, sessionStorage].flatMap((storage) =>
        Array.from({ length: storage.length }, (_, i) => storage.getItem(storage.key(i) ?? '') ?? ''),
      ),
    )
    expect(stored.filter((value) => value.includes('eyJ'))).toEqual([])

    const cookie = (await context.cookies()).find((c) => c.name === 'refresh_token')
    expect(cookie).toMatchObject({ httpOnly: true, sameSite: 'Strict', path: '/api/auth' })
    expect(await page.evaluate(() => document.cookie)).not.toContain('refresh_token')
  })

  test('他人の投稿・コメントは、画面に編集・削除の操作が出ず、API を直接呼んでも 403 で変わらない', async ({
    page,
    request,
  }) => {
    const owner = newUser('max')
    const ownerToken = await signupByApi(request, owner)
    const content = `他人に消されない投稿 ${owner.username}`
    const post = await postByApi(request, ownerToken, content)
    const commentId = await commentByApi(request, ownerToken, post.id, `他人に消されないコメント ${owner.username}`)
    const me = newUser('ned')
    const myToken = await signupByApi(request, me)

    await loginByUi(page, me)
    await page.goto(`/posts/${post.id}`)
    await expect(page.getByText(content)).toBeVisible()
    await expect(postCard(page, content).getByRole('button', { name: 'メニュー' })).toHaveCount(0)
    await expect(page.getByRole('region', { name: 'コメント' }).getByRole('button', { name: '削除' })).toHaveCount(0)

    // 画面でボタンを隠すだけでなく、サーバーでも本人のものかを確かめている
    const headers = { Authorization: `Bearer ${myToken}` }
    expect((await request.put(`/api/posts/${post.id}`, { headers, data: { content: '書き換え' } })).status()).toBe(403)
    expect((await request.delete(`/api/posts/${post.id}`, { headers })).status()).toBe(403)
    expect((await request.delete(`/api/comments/${commentId}`, { headers })).status()).toBe(403)

    await page.reload()
    await expect(page.getByText(content)).toBeVisible()
    await expect(page.getByText(`他人に消されないコメント ${owner.username}`)).toBeVisible()
  })
})
