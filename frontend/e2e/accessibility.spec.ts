import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { commentByApi, followByApi, loginByUi, newUser, postByApi, signupByApi } from './support'

// アクセシビリティの自動チェック（axe-core）。部品のテストでもラベルは確かめているが、色のコントラスト・見出しの順番・
// ランドマークなど、本物のブラウザで画面全体を描いて初めて分かることをここで確かめる。
// 基準は WCAG 2.1 の A・AA。重大（serious・critical）な違反があれば落とす（軽いものは数えない）

const SERIOUS = new Set(['serious', 'critical'])

/** axe の結果の、違反した要素1つ（axe-core は直接の依存ではないので、AxeBuilder の結果の型から取る） */
type NodeResult = Awaited<ReturnType<AxeBuilder['analyze']>>['violations'][number]['nodes'][number]

/**
 * 分かっていて、Issue で直すことにした違反。直したらここから消す（消し忘れないよう、どの Issue の分かを書く）。
 * 規則の種類だけでなく、どの色・どの要素かまで絞って外す（同じ規則でも、新しく出た違反は落とす）
 */
const KNOWN: { issue: string; rule: string; matches: (node: NodeResult) => boolean }[] = [
  {
    issue: '#140',
    rule: 'color-contrast',
    // 基本の青（#1d9bf0）と削除の赤（#f4212e）の文字・背景
    matches: (node) => {
      const data = node.any[0]?.data as { fgColor?: string; bgColor?: string } | undefined
      return [data?.fgColor, data?.bgColor].some((color) => color === '#1d9bf0' || color === '#f4212e')
    },
  },
  {
    issue: '#140',
    rule: 'label-content-name-mismatch',
    // 投稿カードのコメント数のリンク・ヘッダーの自分の名前のリンク
    matches: (node) => node.html.includes('aria-label="コメント（') || node.html.includes('としてログイン中'),
  },
  {
    issue: '#140',
    rule: 'link-name',
    // 投稿詳細の、コメントした人のアイコンのリンク
    matches: (node) => node.html.includes('avatarLink'),
  },
]

/** 画面全体を axe で調べ、重大な違反がないことを確かめる。違反があれば、どの要素かが分かるように出す */
async function expectNoSeriousViolations(page: Page, screen: string) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
  const serious = results.violations
    .filter((v) => SERIOUS.has(v.impact ?? ''))
    .flatMap((v) =>
      v.nodes
        .filter((node) => !KNOWN.some((known) => known.rule === v.id && known.matches(node)))
        .map((node) => `${v.id}（${v.impact}）${v.help}: ${node.html}`),
    )
  expect(serious, `${screen} の重大な違反`).toEqual([])
}

test.describe('アクセシビリティ', () => {
  test('ログイン・新規登録の画面', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'SNSアプリにログイン' })).toBeVisible()
    await expectNoSeriousViolations(page, 'ログイン')

    // 入力のエラーを出した状態も調べる（エラーの文字の色など）
    await page.getByRole('button', { name: 'ログイン' }).click()
    await expectNoSeriousViolations(page, 'ログイン（入力エラー）')

    await page.goto('/signup')
    await expect(page.getByRole('button', { name: '登録する' })).toBeVisible()
    await expectNoSeriousViolations(page, '新規登録')
  })

  test('ログインしたあとの画面', async ({ page, request }) => {
    const friend = newUser('ola')
    const friendToken = await signupByApi(request, friend)
    const post = await postByApi(request, friendToken, `アクセシビリティを調べる投稿 ${friend.username}`)
    await commentByApi(request, friendToken, post.id, 'アクセシビリティを調べるコメント')
    const me = newUser('pia')
    const token = await signupByApi(request, me)
    await followByApi(request, token, friend.username)
    await loginByUi(page, me)

    const screens: [string, string, string][] = [
      ['ホーム（フォロー中）', '/', `アクセシビリティを調べる投稿 ${friend.username}`],
      ['ホーム（全体）', '/all', `アクセシビリティを調べる投稿 ${friend.username}`],
      ['投稿詳細', `/posts/${post.id}`, 'アクセシビリティを調べるコメント'],
      ['プロフィール', `/users/${friend.username}`, `@${friend.username}`],
      ['フォロワー一覧', `/users/${friend.username}/followers`, `@${me.username}`],
      ['ユーザー検索', `/search?q=${friend.username}`, `@${friend.username}`],
      ['プロフィール編集', '/settings/profile', 'プロフィールを編集'],
    ]
    for (const [screen, path, text] of screens) {
      await page.goto(path)
      await expect(page.getByText(text).first(), screen).toBeVisible()
      await expectNoSeriousViolations(page, screen)
    }
  })

  test('開いたダイアログ（投稿の作成・削除の確認）', async ({ page, request }) => {
    const me = newUser('rex')
    const token = await signupByApi(request, me)
    await postByApi(request, token, `ダイアログを調べる投稿 ${me.username}`)
    await loginByUi(page, me)

    await page.getByRole('button', { name: '✏ 投稿する' }).click()
    await expect(page.getByRole('dialog', { name: '新しい投稿' })).toBeVisible()
    await expectNoSeriousViolations(page, '投稿の作成')
    await page.getByRole('button', { name: '閉じる' }).click()

    await page.getByRole('button', { name: 'メニュー' }).first().click()
    await page.getByRole('menuitem', { name: '削除' }).click()
    await expect(page.getByRole('alertdialog')).toBeVisible()
    await expectNoSeriousViolations(page, '削除の確認')
  })
})
