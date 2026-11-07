// CloudFront Functions（spa_rewrite.js）のテスト。node --test infra/cloudfront_functions/*.test.mjs で動かす（CI の Terraform のジョブ）。
// CloudFront Functions は import・export を使えないので、ファイルの中身を読み込んで handler を取り出す
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const code = readFileSync(new URL('./spa_rewrite.js', import.meta.url), 'utf8')
const handler = new Function(`${code}; return handler`)()
const rewrite = (uri) => handler({ request: { uri, method: 'GET', querystring: {}, headers: {} } }).uri

// 同値分割：画面の URL（拡張子なし） / ファイル（最後の部分に「.」がある） / トップ
for (const [uri, expected] of [
  ['/', '/index.html'],
  ['/login', '/index.html'],
  ['/users/yamada_taro', '/index.html'], // ユーザー名は英数字と _ だけ（「.」は使えない）
  ['/users/yamada/followers', '/index.html'],
  ['/posts/123', '/index.html'],
  ['/search', '/index.html'], // 検索の条件（?q=...）は uri に含まれない
  ['/settings/profile', '/index.html'],
  ['/index.html', '/index.html'],
  ['/favicon.svg', '/favicon.svg'],
  ['/assets/index-AbC123.js', '/assets/index-AbC123.js'],
  ['/assets/index-AbC123.css', '/assets/index-AbC123.css'],
  // ない画面（/no-such-page）も index.html にする。画面の側で「ない URL」としてホームに移す
  ['/no-such-page', '/index.html'],
  // 途中のフォルダ名に「.」があっても、最後の部分で決める
  ['/a.b/c', '/index.html'],
]) {
  test(`${uri} → ${expected}`, () => {
    assert.equal(rewrite(uri), expected)
  })
}
