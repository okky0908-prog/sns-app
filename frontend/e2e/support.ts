// E2E テストで共通して使うもの（ユーザーを作る・API で下準備する・ログインする）
import { expect, type APIRequestContext, type Page } from '@playwright/test'

export interface TestUser {
  username: string
  displayName: string
  email: string
  password: string
}

/**
 * 毎回違うユーザー（DB はテストの実行全体で共有しているので、名前が重ならないようにする）。
 * ユーザー名は半角英数字と _ で4〜15文字
 */
export function newUser(label: string): TestUser {
  const id = `${Date.now().toString(36).slice(-6)}${Math.floor(Math.random() * 1296)
    .toString(36)
    .padStart(2, '0')}`
  return {
    username: `${label.slice(0, 6)}_${id}`,
    displayName: `${label}さん`,
    email: `${label}_${id}@example.com`,
    password: 'password123',
  }
}

/** 画面を通さずに、API でユーザーを登録する（その流れの本題ではない下準備）。アクセストークンを返す */
export async function signupByApi(request: APIRequestContext, user: TestUser): Promise<string> {
  const res = await request.post('/api/auth/signup', {
    data: { username: user.username, displayName: user.displayName, email: user.email, password: user.password },
  })
  expect(res.status(), await res.text()).toBe(201)
  return ((await res.json()) as { accessToken: string }).accessToken
}

/** API で投稿する（下準備） */
export async function postByApi(request: APIRequestContext, token: string, content: string) {
  const res = await request.post('/api/posts', {
    headers: { Authorization: `Bearer ${token}` },
    multipart: { content },
  })
  expect(res.status(), await res.text()).toBe(201)
}

/** ログイン画面からログインし、ホームが出るまで待つ */
export async function loginByUi(page: Page, user: TestUser) {
  await page.goto('/login')
  await page.getByLabel('メールアドレス').fill(user.email)
  await page.getByLabel('パスワード', { exact: true }).fill(user.password)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.getByRole('heading', { name: 'ホーム' })).toBeVisible()
}

/** 1×1 の PNG 画像（画像付きの投稿で使う） */
export const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)
