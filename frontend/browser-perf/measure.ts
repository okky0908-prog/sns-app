// ブラウザのパフォーマンステストの道具（docs/performance-test-plan.md「10. ブラウザのパフォーマンステスト」）。
//
// 時間は、テストの道具（Playwright）の側ではなく、ブラウザの中の performance.now() で測る。
// Playwright の expect は画面を数十〜数百 ms おきに見に行くので、その待ち時間が混ざらないようにするため
import type { Locator, Page } from '@playwright/test'

// ===== 測る条件 =====

export interface Condition {
  name: string
  /** CPU を何倍遅くするか（1 ならそのまま。Chrome の DevTools Protocol の Emulation.setCPUThrottlingRate） */
  cpuRate: number
  /** 回線のまね（ms・バイト/秒）。なければそのまま */
  network?: { latency: number; downloadThroughput: number; uploadThroughput: number }
  /** 合否を付けるか（Mac のままの条件だけ付ける。遅い条件は記録だけ） */
  judge: boolean
}

export const CONDITIONS: Condition[] = [
  { name: 'Mac のまま', cpuRate: 1, judge: true },
  {
    // Lighthouse のスマートフォンの条件（CPU 4 倍遅く・遅延 150ms・下り 1.6Mbps）に合わせる
    name: '遅い端末・回線のまね',
    cpuRate: 4,
    network: { latency: 150, downloadThroughput: (1.6 * 1024 * 1024) / 8, uploadThroughput: (750 * 1024) / 8 },
    judge: false,
  },
]

/** ページに条件を当てる（CPU・回線を遅くする） */
export async function applyCondition(page: Page, condition: Condition) {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: condition.cpuRate })
  if (condition.network) {
    await cdp.send('Network.enable')
    await cdp.send('Network.emulateNetworkConditions', { offline: false, ...condition.network })
  }
}

// ===== 最初の表示 =====

/**
 * ページを開く前に入れておく計測（page.addInitScript に渡す）。
 * - LCP（一番大きな要素が描かれた時刻）を記録する
 * - タイムラインの最初の投稿が DOM に現れた時刻を記録する（読み込み直したときに、タイムラインが出るまでの時間）
 */
export function installLoadObserver() {
  const perf = { lcp: 0, firstPostAt: 0 }
  ;(window as unknown as { perfMarks: typeof perf }).perfMarks = perf
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) perf.lcp = entry.startTime
  }).observe({ type: 'largest-contentful-paint', buffered: true })
  new MutationObserver((_, observer) => {
    if (document.querySelector('section[aria-label="投稿一覧"] article')) {
      perf.firstPostAt = performance.now()
      observer.disconnect()
    }
  }).observe(document, { childList: true, subtree: true })
}

export interface LoadMetrics {
  /**
   * HTML が届き終わるまで。最初の1バイト（TTFB）にしないのは、Chrome の回線のまねが、遅延を届き終わりにまとめて足すため
   * （届き始めの時刻は、回線を遅くしても変わらない）
   */
  documentEnd: number
  /** 最初に何かが描かれるまで */
  fcp: number
  /** 一番大きな要素が描かれるまで */
  lcp: number
  /** HTML の読み込みと、すぐ実行するスクリプトが終わるまで */
  domContentLoaded: number
  /** JavaScript・CSS の、実際に送られた量（バイト。圧縮したあと） */
  scriptTransfer: number
  styleTransfer: number
  /** JavaScript の、展開したあとの量（ブラウザが読んで実行する量） */
  scriptDecoded: number
}

/** 開いたページの読み込みの値を集める（LCP が決まるよう、読み込みが終わってから呼ぶ） */
export async function loadMetrics(page: Page): Promise<LoadMetrics> {
  // LCP は描いたあと少し遅れて通知されるので、通知が来るまで待つ
  await page.waitForFunction(() => (window as unknown as { perfMarks: { lcp: number } }).perfMarks.lcp > 0)
  return page.evaluate(() => {
    const nav = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming
    const fcp = performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? 0
    const resources = performance.getEntriesByType('resource') as PerformanceResourceTiming[]
    const sum = (pattern: RegExp, size: (r: PerformanceResourceTiming) => number) =>
      resources.filter((r) => pattern.test(new URL(r.name).pathname)).reduce((total, r) => total + size(r), 0)
    return {
      documentEnd: nav.responseEnd,
      fcp,
      lcp: (window as unknown as { perfMarks: { lcp: number } }).perfMarks.lcp,
      domContentLoaded: nav.domContentLoadedEventEnd,
      scriptTransfer: sum(/\.js$/, (r) => r.transferSize),
      styleTransfer: sum(/\.css$/, (r) => r.transferSize),
      scriptDecoded: sum(/\.js$/, (r) => r.decodedBodySize),
    }
  })
}

/** 読み込み直してから、タイムラインの最初の投稿が DOM に現れるまで（installLoadObserver が記録した時刻） */
export async function firstPostTime(page: Page): Promise<number> {
  await page.waitForFunction(() => (window as unknown as { perfMarks: { firstPostAt: number } }).perfMarks.firstPostAt > 0)
  return page.evaluate(() => (window as unknown as { perfMarks: { firstPostAt: number } }).perfMarks.firstPostAt)
}

// ===== 操作から画面反映まで =====

export interface Until {
  /** これに当てはまる要素が、すべての selector でそろったら「反映した」とみなす */
  selectors: string[]
  /** true なら、操作の前からあった要素は数えない（新しく描かれたものだけ） */
  fresh?: boolean
  /** 要素の文字にこれが含まれること */
  text?: string
}

/**
 * 操作した瞬間から、画面に結果が描かれるフレームまでの時間（ms）。
 * 操作も待ちも、ブラウザの中で行う（Playwright の待ちの間隔を含めないため）。
 * 毎フレーム（requestAnimationFrame）、until がそろったかを確かめ、そろったフレームの時刻を返す
 */
export async function timeAction(target: Locator, how: 'click' | 'submit' | 'scroll', until: Until): Promise<number> {
  return target.evaluate(
    (element, { kind, wait }) =>
      new Promise<number>((resolve, reject) => {
        const OLD = 'data-perf-old'
        if (wait.fresh) {
          for (const selector of wait.selectors) document.querySelectorAll(selector).forEach((e) => e.setAttribute(OLD, ''))
        }
        const ready = () =>
          wait.selectors.every((selector) =>
            Array.from(document.querySelectorAll(selector)).some(
              (e) => (!wait.fresh || !e.hasAttribute(OLD)) && (!wait.text || (e.textContent ?? '').includes(wait.text)),
            ),
          )
        const start = performance.now()
        const tick = () => {
          if (ready()) resolve(performance.now() - start)
          else if (performance.now() - start > 20_000) reject(new Error(`20秒たっても反映しない: ${wait.selectors.join(' / ')}`))
          else requestAnimationFrame(tick)
        }
        if (kind === 'click') (element as HTMLElement).click()
        else if (kind === 'submit') (element as HTMLFormElement).requestSubmit()
        else element.scrollIntoView()
        requestAnimationFrame(tick)
      }),
    { kind: how, wait: until },
  )
}

// ===== 集計 =====

export interface Row {
  name: string
  samples: number[]
  /** 目安（ms・バイト）。なければ記録だけ */
  budget?: number
  unit: 'ms' | 'KB'
}

export function median(values: number[]) {
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

/** 結果を Markdown の表にする（docs/performance-test-results.md にそのまま貼れる形） */
export function toMarkdown(condition: Condition, rows: Row[]) {
  const format = (row: Row, value: number) => (row.unit === 'KB' ? `${(value / 1024).toFixed(1)}KB` : `${Math.round(value)}ms`)
  const lines = [
    `#### ${condition.name}（${rows[0]?.samples.length ?? 0} 回の中央値と最大）`,
    '',
    '| 測るもの | 中央値 | 最大 | 目安 | 合否 |',
    '|---|---|---|---|---|',
  ]
  for (const row of rows) {
    const mid = median(row.samples)
    const verdict = row.budget === undefined || !condition.judge ? '—' : mid <= row.budget ? '合格' : '**不合格**'
    const budget = row.budget === undefined ? '—' : format(row, row.budget)
    lines.push(`| ${row.name} | ${format(row, mid)} | ${format(row, Math.max(...row.samples))} | ${budget} | ${verdict} |`)
  }
  return lines.join('\n')
}
