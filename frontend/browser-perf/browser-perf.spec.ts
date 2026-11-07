import { mkdirSync, writeFileSync } from 'node:fs'
import { expect, test, type Browser, type Page } from '@playwright/test'
import {
  applyCondition,
  CONDITIONS,
  firstPostTime,
  installLoadObserver,
  loadMetrics,
  median,
  pageCls,
  timeAction,
  toMarkdown,
  type Condition,
  type Row,
} from './measure'

// ブラウザのパフォーマンステスト（docs/performance-test-plan.md「10. ブラウザのパフォーマンステスト」）。
// 本番用にビルドした画面を、性能テストの DB（sns_perf・基準の規模）につないだバックエンドで開いて測る。
// 合否の目安：操作から画面反映まで 1 秒以内（非機能要件「性能」）、LCP 2.5 秒以内・CLS 0.1 以下（Web Vitals の「良い」）

const BASE_URL = 'http://localhost:5173'
/** たくさんフォローしている人（perf/data/seed.sql。タイムラインが一番重い） */
const USER = { email: 'perf00001@example.com', password: 'password123' }
/** 1 つの値を何回測るか（最初の 1 回は温めるために捨てる） */
const SAMPLES = Number(process.env.SAMPLES ?? 5)
const BUDGET_MS = { interaction: 1000, timeline: 1000, lcp: 2500 }
const BUDGET_CLS = 0.1

const LIST_ARTICLE = 'section[aria-label="投稿一覧"][aria-busy="false"] article'

test.beforeAll(async ({ playwright }) => {
  // 性能テストの DB につないだバックエンドが動いているか（手順は計画書の「10」）
  const api = await playwright.request.newContext({ baseURL: BASE_URL })
  const res = await api.post('/api/auth/login', { data: USER }).catch(() => null)
  expect(
    res?.status(),
    'perf00001 でログインできません。perf/setup-db.sh base のあと、バックエンドを POSTGRES_DB=sns_perf で 8080 番に起動してください',
  ).toBe(200)
  await api.dispose()
})

/** 結果を表にして、画面・レポート・ファイル（browser-perf-results/。Git には入れない）に出す。目安を超えたら落とす */
async function report(condition: Condition, title: string, rows: Row[]) {
  const markdown = `### ${title}\n\n${toMarkdown(condition, rows)}\n`
  console.log(`\n${markdown}`)
  await test.info().attach(`${title}（${condition.name}）`, { body: markdown, contentType: 'text/markdown' })
  mkdirSync('browser-perf-results', { recursive: true })
  writeFileSync(`browser-perf-results/${title}-${condition.name}.md`, markdown)
  if (!condition.judge) return
  for (const row of rows) {
    if (row.budget !== undefined) expect.soft(median(row.samples), row.name).toBeLessThanOrEqual(row.budget)
  }
}

async function newPage(browser: Browser, condition: Condition) {
  const context = await browser.newContext({ baseURL: BASE_URL, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await context.newPage()
  await page.addInitScript(installLoadObserver)
  await applyCondition(page, condition)
  return page
}

/** ホームを開き、タイムラインが出るまで待つ（測る前の準備。ここは測らない） */
async function openHome(page: Page) {
  await page.goto('/')
  await expect(page.locator(LIST_ARTICLE).first()).toBeVisible({ timeout: 30_000 })
}

for (const condition of CONDITIONS) {
  test.describe(condition.name, () => {
    test('最初の表示', async ({ browser }) => {
      const rows: Record<string, Row> = {
        html: { name: 'ログイン画面：HTML が届き終わるまで', samples: [], unit: 'ms' },
        fcp: { name: 'ログイン画面：最初に描かれるまで（FCP）', samples: [], unit: 'ms' },
        lcp: { name: 'ログイン画面：一番大きな要素まで（LCP）', samples: [], unit: 'ms', budget: BUDGET_MS.lcp },
        dcl: { name: 'ログイン画面：DOMContentLoaded', samples: [], unit: 'ms' },
        clsLogin: { name: 'ログイン画面：レイアウトのずれ（CLS）', samples: [], unit: 'score', budget: BUDGET_CLS },
        login: { name: '「ログイン」を押してからタイムラインが出るまで', samples: [], unit: 'ms', budget: BUDGET_MS.timeline },
        reload: { name: '読み込み直してからタイムラインが出るまで（再訪問）', samples: [], unit: 'ms', budget: BUDGET_MS.timeline },
        // ログインのボタンを押した直後のずれは「操作の結果」として数えないので（Web Vitals）、読み込み直したときで測る
        clsTimeline: {
          name: 'タイムライン（再訪問）：レイアウトのずれ（CLS。画像・アイコンが出そろうまで）',
          samples: [],
          unit: 'score',
          budget: BUDGET_CLS,
        },
        scriptTransfer: { name: 'JavaScript の量（送られた量。圧縮後）', samples: [], unit: 'KB' },
        scriptDecoded: { name: 'JavaScript の量（展開後）', samples: [], unit: 'KB' },
        styleTransfer: { name: 'CSS の量（送られた量。圧縮後）', samples: [], unit: 'KB' },
      }
      for (let i = 0; i <= SAMPLES; i++) {
        // 毎回新しいブラウザの状態（キャッシュ・Cookie なし）で開く
        const page = await newPage(browser, condition)
        await page.goto('/login', { waitUntil: 'load' })
        await expect(page.getByRole('button', { name: 'ログイン' })).toBeVisible()
        const load = await loadMetrics(page)
        const clsLogin = await pageCls(page)

        await page.getByLabel('メールアドレス').fill(USER.email)
        await page.getByLabel('パスワード', { exact: true }).fill(USER.password)
        const login = await timeAction(page.getByRole('button', { name: 'ログイン' }), 'click', { selectors: [LIST_ARTICLE] })

        await page.reload()
        const reload = await firstPostTime(page)
        const clsTimeline = await pageCls(page)
        await page.context().close()

        if (i === 0) continue // 1 回目は、サーバー・ブラウザを温めるために捨てる
        rows.html.samples.push(load.documentEnd)
        rows.fcp.samples.push(load.fcp)
        rows.lcp.samples.push(load.lcp)
        rows.dcl.samples.push(load.domContentLoaded)
        rows.clsLogin.samples.push(clsLogin)
        rows.clsTimeline.samples.push(clsTimeline)
        rows.login.samples.push(login)
        rows.reload.samples.push(reload)
        rows.scriptTransfer.samples.push(load.scriptTransfer)
        rows.scriptDecoded.samples.push(load.scriptDecoded)
        rows.styleTransfer.samples.push(load.styleTransfer)
      }
      await report(condition, '最初の表示', Object.values(rows))
    })

    test('操作から画面反映まで', async ({ browser }) => {
      test.setTimeout(10 * 60_000)
      const page = await newPage(browser, condition)
      await page.goto('/login')
      await page.getByLabel('メールアドレス').fill(USER.email)
      await page.getByLabel('パスワード', { exact: true }).fill(USER.password)
      await page.getByRole('button', { name: 'ログイン' }).click()
      await expect(page.locator(LIST_ARTICLE).first()).toBeVisible({ timeout: 30_000 })

      const actions: { name: string; run: () => Promise<number> }[] = [
        {
          name: 'タブの切り替え（フォロー中 → 全体）',
          run: () =>
            timeAction(
              page.getByRole('navigation', { name: 'タイムラインの切り替え' }).getByRole('link', { name: '全体' }),
              'click',
              { selectors: [LIST_ARTICLE], fresh: true },
            ),
        },
        {
          name: '続きの読み込み（下までスクロール → 次の20件）',
          run: () => timeAction(page.getByTestId('timeline-sentinel'), 'scroll', { selectors: [LIST_ARTICLE], fresh: true }),
        },
        {
          name: '投稿詳細を開く（投稿とコメントが出るまで）',
          run: () =>
            timeAction(page.locator(LIST_ARTICLE).first().locator('a:has(time)'), 'click', {
              selectors: ['article[data-post-id]', 'section[aria-label="コメント"] ul'],
              fresh: true,
            }),
        },
        {
          name: 'プロフィールを開く（プロフィールと投稿が出るまで）',
          run: () =>
            // 投稿者の名前のリンク（1 つ目はアイコン、2 つ目が名前）
            timeAction(page.locator(LIST_ARTICLE).first().locator('a[href^="/users/"]').nth(1), 'click', {
              selectors: ['section[aria-label="プロフィール"]', LIST_ARTICLE],
              fresh: true,
            }),
        },
        {
          name: 'ユーザー検索（ヘッダーで「perf0042」→ 結果が出るまで）',
          run: async () => {
            const box = page.getByRole('banner').getByRole('searchbox', { name: 'ユーザーを検索' })
            await box.fill('perf0042')
            return timeAction(box.locator('xpath=ancestor::form'), 'submit', { selectors: ['main li[data-user-id]'], fresh: true })
          },
        },
        {
          name: 'いいね（押してから表示が変わるまで）',
          run: async () => {
            const card = page.locator(LIST_ARTICLE).first()
            await card.evaluate((e) => e.setAttribute('data-perf-target', ''))
            const pressed = await card.getByRole('button', { name: /いいね/ }).getAttribute('aria-pressed')
            const next = pressed === 'true' ? 'false' : 'true'
            const saved = page.waitForResponse((r) => r.url().includes('/likes'))
            const ms = await timeAction(card.getByRole('button', { name: /いいね/ }), 'click', {
              selectors: [`article[data-perf-target] button[aria-pressed="${next}"]`],
            })
            await saved // 次の操作の前に、API の結果まで待つ（途中で画面を移ると、取り消されることがある）
            return ms
          },
        },
        {
          name: '投稿する（「投稿する」を押してから先頭に出るまで）',
          run: async () => {
            await page.getByRole('button', { name: '✏ 投稿する' }).click()
            const dialog = page.getByRole('dialog', { name: '新しい投稿' })
            const text = `ブラウザの性能を測る投稿 ${Date.now()}`
            await dialog.getByRole('textbox', { name: '本文' }).fill(text)
            return timeAction(dialog.getByRole('button', { name: '投稿する' }), 'click', {
              selectors: [LIST_ARTICLE],
              fresh: true,
              text,
            })
          },
        },
      ]

      const rows: Row[] = actions.map((a) => ({ name: a.name, samples: [], unit: 'ms', budget: BUDGET_MS.interaction }))
      for (let i = 0; i <= SAMPLES; i++) {
        for (const [index, action] of actions.entries()) {
          await openHome(page)
          const ms = await action.run()
          if (i > 0) rows[index].samples.push(ms) // 1 回目は温めるために捨てる
        }
      }
      await report(condition, '操作から画面反映まで', rows)
    })
  })
}
