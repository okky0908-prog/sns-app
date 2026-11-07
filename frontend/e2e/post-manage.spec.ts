import { expect, test } from '@playwright/test'
import { commentByApi, likeByApi, loginByUi, newUser, postByApi, postCard, signupByApi, TINY_PNG } from './support'

// 自分の投稿の編集・削除（docs/screens.md の UC-05・UC-06）
test.describe('投稿の編集・削除', () => {
  test('自分の投稿を編集すると、本文が変わって「編集済み」と出て、読み込み直しても残っている（UC-05）', async ({
    page,
    request,
  }) => {
    const me = newUser('henry')
    const token = await signupByApi(request, me)
    const before = `編集する前の本文 ${me.username}`
    const after = `編集したあとの本文 ${me.username}`
    await postByApi(request, token, before)
    await loginByUi(page, me)

    await postCard(page, before).getByRole('button', { name: 'メニュー' }).click()
    await page.getByRole('menuitem', { name: '編集' }).click()
    const dialog = page.getByRole('dialog', { name: '投稿を編集' })
    await expect(dialog.getByRole('textbox', { name: '本文' })).toHaveValue(before)
    await dialog.getByRole('textbox', { name: '本文' }).fill(after)
    await dialog.getByRole('button', { name: '保存する' }).click()

    await expect(dialog).toBeHidden()
    await expect(page.getByRole('status')).toHaveText('投稿を更新しました')
    await expect(postCard(page, after)).toContainText('編集済み')
    await expect(postCard(page, before)).toHaveCount(0)

    await page.reload()
    await expect(postCard(page, after)).toContainText('編集済み')
  })

  test('自分の投稿を削除すると、確認のあとに消え、詳細・コメント・画像も見られなくなる。キャンセルしたら残る（UC-06）', async ({
    page,
    request,
  }) => {
    const me = newUser('irene')
    const token = await signupByApi(request, me)
    const content = `削除する投稿 ${me.username}`
    const post = await postByApi(request, token, content, { name: 'dot.png', mimeType: 'image/png', buffer: TINY_PNG })
    const other = newUser('jack')
    const otherToken = await signupByApi(request, other)
    await likeByApi(request, otherToken, post.id)
    await commentByApi(request, otherToken, post.id, '消える投稿へのコメント')
    // 削除する前は、画像を S3（LocalStack）から読み込める
    expect((await request.get(post.images[0].url)).status()).toBe(200)

    await loginByUi(page, me)
    const card = postCard(page, content)
    await expect(card.getByRole('link', { name: 'コメント（1件）' })).toBeVisible()

    await test.step('確認ダイアログでキャンセルしたら、投稿は残る', async () => {
      await card.getByRole('button', { name: 'メニュー' }).click()
      await page.getByRole('menuitem', { name: '削除' }).click()
      await page.getByRole('alertdialog').getByRole('button', { name: 'キャンセル' }).click()
      await expect(page.getByRole('alertdialog')).toBeHidden()
      await expect(card).toBeVisible()
    })

    await test.step('確認ダイアログで削除したら、一覧から消え、読み込み直しても戻らない', async () => {
      await card.getByRole('button', { name: 'メニュー' }).click()
      await page.getByRole('menuitem', { name: '削除' }).click()
      await expect(page.getByRole('alertdialog')).toContainText('この投稿を削除しますか？')
      await page.getByRole('alertdialog').getByRole('button', { name: '削除する' }).click()

      await expect(page.getByRole('status')).toHaveText('投稿を削除しました')
      await expect(card).toHaveCount(0)
      await page.reload()
      await expect(page.getByRole('heading', { name: 'ホーム' })).toBeVisible()
      await expect(card).toHaveCount(0)
    })

    await test.step('投稿の詳細・コメント・画像も消えている', async () => {
      const headers = { Authorization: `Bearer ${otherToken}` }
      expect((await request.get(`/api/posts/${post.id}`, { headers })).status()).toBe(404)
      expect((await request.get(`/api/posts/${post.id}/comments`, { headers })).status()).toBe(404)
      // 画像は DB の確定のあとに消すので、少し待つことがある
      await expect.poll(async () => (await request.get(post.images[0].url)).status()).not.toBe(200)
    })
  })
})
