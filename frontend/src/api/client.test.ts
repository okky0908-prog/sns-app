// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

/*
 * api/client.ts の単体テスト（fetch は偽物）。テスト計画書 5.4 の状態遷移で考える。
 *
 *   ログイン（トークンを持つ）→ API が 401 → 再発行 → 1回だけやり直す → 成功
 *                                       └ 再発行も失敗・やり直しも 401 → トークンを捨てて、ログアウトの処理を呼ぶ
 *   同時に2つの API が 401 → 再発行は1回にまとめる（リフレッシュトークンは一度使うと交換されるため）
 *
 * client.ts はファイル全体でアクセストークンなどの状態を持つので、テストのたびに読み込み直して、まっさらな状態から始める。
 */

type Client = typeof import('./client')

let client: Client
const fetchMock = vi.fn<typeof fetch>()

const USER = { id: 1, username: 'alice', displayName: 'アリス', iconUrl: null }

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const apiError = (status: number, code: string, errors: unknown[] = []) =>
  json(status, { status, code, message: `${code} の文言`, errors })
const authResponse = (token: string) => json(200, { accessToken: token, user: USER })

/** n 回目の fetch の URL と中身 */
const call = (n: number) => {
  const [url, init] = fetchMock.mock.calls[n]
  return { url: String(url), init: init ?? {}, headers: (init?.headers ?? {}) as Record<string, string> }
}
const urls = () => fetchMock.mock.calls.map(([url]) => String(url))

beforeEach(async () => {
  vi.resetModules()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  client = await import('./client')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** ログインして、アクセストークン token を持った状態にする（fetch の呼び出しの記録は消す） */
async function loginWith(token: string) {
  fetchMock.mockResolvedValueOnce(authResponse(token))
  await client.login({ email: 'alice@example.com', password: 'password123' })
  fetchMock.mockClear()
}

describe('アクセストークンの扱い', () => {
  test('ログインすると、ログインが必要な API にアクセストークンを付ける', async () => {
    await loginWith('token-1')
    fetchMock.mockResolvedValueOnce(json(200, USER))

    await expect(client.fetchMe()).resolves.toEqual(USER)
    expect(call(0).headers.Authorization).toBe('Bearer token-1')
  })

  test('ログインなしで呼べる API（ログイン）には、アクセストークンを付けない', async () => {
    fetchMock.mockResolvedValueOnce(authResponse('token-1'))

    await client.login({ email: 'alice@example.com', password: 'password123' })

    expect(call(0).headers.Authorization).toBeUndefined()
    expect(call(0).init.method).toBe('POST')
  })

  test('アクセストークンは localStorage に保存しない。以前の版が保存したものは読み込み時に消す', async () => {
    localStorage.setItem('sns-app.token', 'old')
    vi.resetModules()
    client = await import('./client')
    await loginWith('token-1')

    expect(localStorage.getItem('sns-app.token')).toBeNull()
    expect(Object.keys(localStorage)).toEqual([])
  })
})

describe('再発行とやり直し（状態遷移）', () => {
  test('401 なら再発行し、新しいトークンで1回だけやり直す', async () => {
    await loginWith('token-1')
    fetchMock
      .mockResolvedValueOnce(apiError(401, 'UNAUTHENTICATED'))
      .mockResolvedValueOnce(authResponse('token-2'))
      .mockResolvedValueOnce(json(200, USER))
    const onUnauthorized = vi.fn()
    client.setUnauthorizedHandler(onUnauthorized)

    await expect(client.fetchMe()).resolves.toEqual(USER)

    expect(urls()).toEqual(['/api/auth/me', '/api/auth/refresh', '/api/auth/me'])
    expect(call(1).init.method).toBe('POST')
    expect(call(2).headers.Authorization).toBe('Bearer token-2')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  test('再発行も失敗したら、トークンを捨ててログアウトの処理を呼び、再発行のエラーを返す', async () => {
    await loginWith('token-1')
    fetchMock
      .mockResolvedValueOnce(apiError(401, 'UNAUTHENTICATED'))
      .mockResolvedValueOnce(apiError(401, 'SESSION_EXPIRED'))
    const onUnauthorized = vi.fn()
    client.setUnauthorizedHandler(onUnauthorized)

    await expect(client.fetchMe()).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED' })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)

    // トークンを捨てたので、次の呼び出しには付けない
    fetchMock.mockResolvedValueOnce(json(200, USER))
    await client.fetchMe()
    expect(call(2).headers.Authorization).toBeUndefined()
  })

  test('やり直しも 401 なら、トークンを捨ててログアウトの処理を呼び、それ以上やり直さない', async () => {
    await loginWith('token-1')
    fetchMock
      .mockResolvedValueOnce(apiError(401, 'UNAUTHENTICATED'))
      .mockResolvedValueOnce(authResponse('token-2'))
      .mockResolvedValueOnce(apiError(401, 'UNAUTHENTICATED'))
    const onUnauthorized = vi.fn()
    client.setUnauthorizedHandler(onUnauthorized)

    await expect(client.fetchMe()).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  test('やり直しが 401 以外のエラーなら、ログアウトの処理は呼ばない', async () => {
    await loginWith('token-1')
    fetchMock
      .mockResolvedValueOnce(apiError(401, 'UNAUTHENTICATED'))
      .mockResolvedValueOnce(authResponse('token-2'))
      .mockResolvedValueOnce(apiError(404, 'POST_NOT_FOUND'))
    const onUnauthorized = vi.fn()
    client.setUnauthorizedHandler(onUnauthorized)

    await expect(client.fetchPost(9)).rejects.toMatchObject({ code: 'POST_NOT_FOUND' })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  test.each([
    [403, 'FORBIDDEN'],
    [404, 'POST_NOT_FOUND'],
    [400, 'VALIDATION_FAILED'],
  ])('%i（%s）は再発行せず、そのままエラーにする', async (status, code) => {
    await loginWith('token-1')
    fetchMock.mockResolvedValueOnce(apiError(status, code))

    await expect(client.deletePost(9)).rejects.toMatchObject({ status, code })
    expect(urls()).toEqual(['/api/posts/9'])
  })

  test('ログインなしで呼べる API の 401（パスワード違い）は、再発行しない', async () => {
    fetchMock.mockResolvedValueOnce(apiError(401, 'INVALID_CREDENTIALS'))

    await expect(client.login({ email: 'a@example.com', password: 'wrong' })).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
    })
    expect(urls()).toEqual(['/api/auth/login'])
  })

  // ログインなしで呼ぶ API では、どんな 401 でも再発行しない（ログインの前に再発行を繰り返さないように）。
  // パスワード違い（INVALID_CREDENTIALS）だけを試すと、code が違うので条件を1つ消しても気づけない（コードをわざと壊して見つけた穴）
  test('ログインなしで呼ぶ API は、UNAUTHENTICATED の 401 でも再発行しない', async () => {
    fetchMock.mockResolvedValueOnce(apiError(401, 'UNAUTHENTICATED'))

    await expect(
      client.signup({ username: 'yamada', displayName: '山田', email: 'y@example.com', password: 'password123' }),
    ).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
    expect(urls()).toEqual(['/api/auth/signup'])
  })

  test('同時に2つの API が 401 になっても、再発行は1回にまとめる', async () => {
    await loginWith('token-1')
    let meCalls = 0
    fetchMock.mockImplementation(async (url) => {
      if (url === '/api/auth/refresh') return authResponse('token-2')
      meCalls += 1
      return meCalls <= 2 ? apiError(401, 'UNAUTHENTICATED') : json(200, USER)
    })

    await Promise.all([client.fetchMe(), client.fetchMe()])

    expect(urls().filter((url) => url === '/api/auth/refresh')).toHaveLength(1)
  })

  test('再発行が終わったら、次の再発行はまた送る（まとめるのは同時のものだけ）', async () => {
    fetchMock.mockResolvedValueOnce(authResponse('token-2')).mockResolvedValueOnce(authResponse('token-3'))

    await client.refresh()
    await client.refresh()

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

describe('ログアウト', () => {
  test('サーバーでリフレッシュトークンを無効にし、手元のアクセストークンも捨てる', async () => {
    await loginWith('token-1')
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))

    await client.logout()

    expect(call(0).url).toBe('/api/auth/logout')
    expect(call(0).init.method).toBe('POST')
    fetchMock.mockResolvedValueOnce(json(200, USER))
    await client.fetchMe()
    expect(call(1).headers.Authorization).toBeUndefined()
  })

  test('通信に失敗しても、手元のアクセストークンは捨てる', async () => {
    await loginWith('token-1')
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await expect(client.logout()).rejects.toMatchObject({ code: 'NETWORK_ERROR' })

    fetchMock.mockResolvedValueOnce(json(200, USER))
    await client.fetchMe()
    expect(call(1).headers.Authorization).toBeUndefined()
  })
})

describe('エラーの変換（同値分割）', () => {
  test('通信そのものに失敗したら、status 0 の NETWORK_ERROR', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    const error = await client.login({ email: 'a@example.com', password: 'x' }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(client.ApiError)
    expect(error).toMatchObject({ status: 0, code: 'NETWORK_ERROR', message: client.NETWORK_ERROR_MESSAGE })
  })

  test('API の形でないエラー（途中のプロキシのエラーページなど）は UNKNOWN', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<html>Bad Gateway</html>', { status: 502 }))

    await expect(client.login({ email: 'a@example.com', password: 'x' })).rejects.toMatchObject({
      status: 502,
      code: 'UNKNOWN',
      message: client.NETWORK_ERROR_MESSAGE,
    })
  })

  test('JSON でも code や message がなければ UNKNOWN', async () => {
    fetchMock.mockResolvedValueOnce(json(500, { error: 'Internal Server Error' }))

    await expect(client.login({ email: 'a@example.com', password: 'x' })).rejects.toMatchObject({ code: 'UNKNOWN' })
  })

  test('API のエラーは、code・文言・項目ごとのエラーをそのまま持つ', async () => {
    fetchMock.mockResolvedValueOnce(
      apiError(400, 'VALIDATION_FAILED', [{ field: 'username', message: 'ユーザー名の文言' }]),
    )

    await expect(
      client.signup({ username: 'ab', displayName: 'x', email: 'a@example.com', password: 'password123' }),
    ).rejects.toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
      message: 'VALIDATION_FAILED の文言',
      fieldErrors: [{ field: 'username', message: 'ユーザー名の文言' }],
    })
  })

  test('項目ごとのエラーがなければ空の配列', async () => {
    fetchMock.mockResolvedValueOnce(json(409, { status: 409, code: 'ALREADY_REGISTERED', message: '文言' }))

    await expect(client.login({ email: 'a@example.com', password: 'x' })).rejects.toMatchObject({ fieldErrors: [] })
  })

  test('errorMessage は、API のエラーならその文言、それ以外は通信エラーの文言', () => {
    expect(client.errorMessage(new client.ApiError(404, 'POST_NOT_FOUND', 'この投稿は見つかりません'))).toBe(
      'この投稿は見つかりません',
    )
    expect(client.errorMessage(new Error('想定外'))).toBe(client.NETWORK_ERROR_MESSAGE)
  })

  test('isApiError は、API のエラーで code が同じときだけ真', () => {
    const error = new client.ApiError(403, 'FORBIDDEN', '文言')
    expect(client.isApiError(error, 'FORBIDDEN')).toBe(true)
    expect(client.isApiError(error, 'POST_NOT_FOUND')).toBe(false)
    expect(client.isApiError(new Error('x'), 'FORBIDDEN')).toBe(false)
  })
})

describe('リクエストの組み立て', () => {
  beforeEach(async () => {
    await loginWith('token-1')
  })

  test('JSON の API は Content-Type を付けて JSON にして送る', async () => {
    fetchMock.mockResolvedValueOnce(json(200, {}))

    await client.updatePost(9, '新しい本文')

    expect(call(0).url).toBe('/api/posts/9')
    expect(call(0).init.method).toBe('PUT')
    expect(call(0).headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(String(call(0).init.body))).toEqual({ content: '新しい本文' })
  })

  test('画像を送る API は FormData のまま送り、Content-Type はブラウザに任せる', async () => {
    fetchMock.mockResolvedValueOnce(json(201, {}))
    const a = new File(['a'], 'a.png', { type: 'image/png' })
    const b = new File(['b'], 'b.gif', { type: 'image/gif' })

    await client.createPost('本文', [a, b])

    const body = call(0).init.body as FormData
    expect(call(0).headers['Content-Type']).toBeUndefined()
    expect(body.get('content')).toBe('本文')
    expect(body.getAll('images').map((f) => (f as File).name)).toEqual(['a.png', 'b.gif'])
  })

  test('プロフィール編集は、アイコンを選んだときだけ送る', async () => {
    fetchMock.mockImplementation(async () => json(200, {}))
    const icon = new File(['i'], 'me.png', { type: 'image/png' })

    await client.updateProfile({ displayName: 'アリス', bio: '', icon: null })
    await client.updateProfile({ displayName: 'アリス', bio: '自己紹介', icon })

    expect((call(0).init.body as FormData).has('icon')).toBe(false)
    expect((call(0).init.body as FormData).get('bio')).toBe('')
    expect(((call(1).init.body as FormData).get('icon') as File).name).toBe('me.png')
  })

  test('削除などの 204 は、本文を読まずに undefined を返す', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))

    await expect(client.deletePost(9)).resolves.toBeUndefined()
    expect(call(0).init.method).toBe('DELETE')
  })

  test('カーソルとユーザー名は URL 用にエスケープする', async () => {
    fetchMock.mockImplementation(async () => json(200, { items: [], nextCursor: null, hasNext: false }))

    await client.fetchTimeline('following', 'a+b/c=')
    await client.fetchTimeline('all')
    await client.fetchUserPosts('a&b', 'x y')
    await client.fetchFollowList('alice', 'followers', 'c1')

    expect(urls()).toEqual([
      '/api/timeline?cursor=a%2Bb%2Fc%3D',
      '/api/timeline/all',
      '/api/users/a%26b/posts?cursor=x%20y',
      '/api/users/alice/followers?cursor=c1',
    ])
  })

  test('一覧の API は、カーソルを指定したときだけ付ける', async () => {
    fetchMock.mockImplementation(async () => json(200, { items: [], nextCursor: null, hasNext: false }))

    await client.fetchComments(9)
    await client.fetchComments(9, 'c1')
    await client.fetchUserPosts('alice')
    await client.fetchFollowList('alice', 'following')

    expect(urls()).toEqual([
      '/api/posts/9/comments',
      '/api/posts/9/comments?cursor=c1',
      '/api/users/alice/posts',
      '/api/users/alice/following',
    ])
  })

  // API の取り決め（docs/api.md の API 一覧）と、送る URL・メソッドが同じか
  test.each<[string, (c: Client) => Promise<unknown>, string, string]>([
    ['A-03 ログイン中のユーザー', (c) => c.fetchMe(), 'GET', '/api/auth/me'],
    ['A-12 投稿詳細', (c) => c.fetchPost(9), 'GET', '/api/posts/9'],
    ['A-13 投稿編集', (c) => c.updatePost(9, 'x'), 'PUT', '/api/posts/9'],
    ['A-14 投稿削除', (c) => c.deletePost(9), 'DELETE', '/api/posts/9'],
    ['A-11 投稿作成', (c) => c.createPost('x'), 'POST', '/api/posts'],
    ['A-31 コメント投稿', (c) => c.createComment(9, 'x'), 'POST', '/api/posts/9/comments'],
    ['A-32 コメント削除', (c) => c.deleteComment(5), 'DELETE', '/api/comments/5'],
    ['A-40 いいね', (c) => c.likePost(9), 'POST', '/api/posts/9/likes'],
    ['A-41 いいね取り消し', (c) => c.unlikePost(9), 'DELETE', '/api/posts/9/likes'],
    ['A-50 フォロー', (c) => c.followUser('bob'), 'POST', '/api/users/bob/follow'],
    ['A-51 フォロー解除', (c) => c.unfollowUser('bob'), 'DELETE', '/api/users/bob/follow'],
    ['A-60 プロフィール', (c) => c.fetchProfile('bob'), 'GET', '/api/users/bob'],
    ['A-62 プロフィール編集', (c) => c.updateProfile({ displayName: 'x', bio: '', icon: null }), 'PUT', '/api/users/me'],
  ])('%s は %s %s に送り、アクセストークンを付ける', async (_label, run, method, url) => {
    fetchMock.mockImplementation(async () => json(200, {}))

    await run(client)

    expect(call(0).url).toBe(url)
    expect(call(0).init.method ?? 'GET').toBe(method)
    expect(call(0).headers.Authorization).toBe('Bearer token-1')
  })

  test('検索のキーワードは URL 用にエスケープし、カーソルは指定したときだけ付ける', async () => {
    fetchMock.mockImplementation(async () => json(200, { items: [], nextCursor: null, hasNext: false }))

    await client.searchUsers('@yama & co')
    await client.searchUsers('', 'c1')

    expect(urls()).toEqual(['/api/users/search?q=%40yama+%26+co', '/api/users/search?q=&cursor=c1'])
  })

  test('新しい投稿の件数は、タブで URL を変え、件数だけを返す', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { count: 3 })).mockResolvedValueOnce(json(200, { count: 100 }))

    await expect(client.fetchNewPostCount('following', 30)).resolves.toBe(3)
    await expect(client.fetchNewPostCount('all', 30)).resolves.toBe(100)
    expect(urls()).toEqual(['/api/timeline/new-count?since=30', '/api/timeline/all/new-count?since=30'])
  })
})
