import { expect, test, type Page } from '@playwright/test'
import { loginByUi, newUser, postByApi, signupByApi } from './support'

// 読み込み中の表示（「読み込み中…」）から本物の画面に切り替わるときに、レイアウトがずれない（#153）。
// ずれの量（CLS）はブラウザのパフォーマンステストで測るが、ここではずれが「起きないこと」を毎回（CI でも）確かめる

/** ページを開く前に入れておく、レイアウトのずれ（layout-shift）の記録 */
function recordLayoutShifts() {
  const shifts: string[] = []
  ;(window as unknown as { layoutShifts: string[] }).layoutShifts = shifts
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries() as unknown as {
      value: number
      hadRecentInput: boolean
      sources: { node?: Node; previousRect: DOMRectReadOnly; currentRect: DOMRectReadOnly }[]
    }[]) {
      if (entry.hadRecentInput) continue // 操作の直後のずれは、操作の結果として数えない（Web Vitals と同じ）
      for (const source of entry.sources) {
        const name = source.node instanceof Element ? source.node.tagName.toLowerCase() : String(source.node?.nodeName)
        shifts.push(`${name}: y ${source.previousRect.y} → ${source.currentRect.y}（${entry.value.toFixed(4)}）`)
      }
    }
  }).observe({ type: 'layout-shift', buffered: true })
}

/** 通信が落ち着いてから、それまでのずれを返す */
async function layoutShifts(page: Page) {
  await page.waitForLoadState('networkidle')
  return page.evaluate(() => (window as unknown as { layoutShifts: string[] }).layoutShifts)
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(recordLayoutShifts)
})

test('ログイン画面を開くとき（ログインの確認中 → ログイン画面）、レイアウトがずれない', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible()

  expect(await layoutShifts(page)).toEqual([])
})

test('タイムラインを読み込み直すとき（読み込み中 → 投稿の一覧）、レイアウトがずれない', async ({ page, request }) => {
  const me = newUser('lsh')
  const token = await signupByApi(request, me)
  await postByApi(request, token, `ずれを調べる投稿 ${me.username}`)
  await loginByUi(page, me)

  await page.reload()
  await expect(page.getByText(`ずれを調べる投稿 ${me.username}`)).toBeVisible()

  expect(await layoutShifts(page)).toEqual([])
})
