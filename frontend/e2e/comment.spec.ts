import { expect, test } from '@playwright/test'
import { commentByApi, loginByUi, newUser, postByApi, signupByApi } from './support'

// コメント（docs/screens.md の UC-11）
test('自分のコメントだけ削除でき、確認のあとに消えてコメント数が1減る（UC-11）', async ({ page, request }) => {
  const me = newUser('quinn')
  const token = await signupByApi(request, me)
  const post = await postByApi(request, token, `コメントを消す投稿 ${me.username}`)
  const other = newUser('ruth')
  const otherToken = await signupByApi(request, other)
  await commentByApi(request, otherToken, post.id, `ほかの人のコメント ${other.username}`)
  await commentByApi(request, token, post.id, `自分のコメント ${me.username}`)

  await loginByUi(page, me)
  await page.goto(`/posts/${post.id}`)
  const comments = page.getByRole('region', { name: 'コメント' })
  const mine = comments.getByRole('listitem').filter({ hasText: `自分のコメント ${me.username}` })
  const others = comments.getByRole('listitem').filter({ hasText: `ほかの人のコメント ${other.username}` })
  await expect(page.getByRole('link', { name: 'コメント（2件）' })).toBeVisible()

  // ほかの人のコメントには、削除のボタンを出さない
  await expect(others.getByRole('button', { name: '削除' })).toHaveCount(0)

  await mine.getByRole('button', { name: '削除' }).click()
  const dialog = page.getByRole('alertdialog', { name: 'このコメントを削除しますか？' })
  await dialog.getByRole('button', { name: '削除する' }).click()

  await expect(mine).toHaveCount(0)
  await expect(others).toBeVisible()
  await expect(page.getByRole('link', { name: 'コメント（1件）' })).toBeVisible()

  await page.reload()
  await expect(others).toBeVisible()
  await expect(mine).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'コメント（1件）' })).toBeVisible()
})
