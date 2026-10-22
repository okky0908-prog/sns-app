// @vitest-environment jsdom
import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, followUser, searchUsers } from '../api/client'
import type { CursorPage, UserListItem } from '../api/types'
import { aListItem, aPage, deferred, FakeIntersectionObserver, renderWithRouter } from '../test/helpers'
import { SearchRoute } from './SearchPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  searchUsers: vi.fn(),
  followUser: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(searchUsers).mockReset().mockResolvedValue(aPage([]))
  vi.mocked(followUser).mockReset()
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
  FakeIntersectionObserver.active.clear()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const users = (...ids: number[]) => ids.map((id) => aListItem({ id, username: `user${id}`, displayName: `ユーザー${id}` }))

function renderSearch(route = '/search') {
  return renderWithRouter(
    <>
      {/* ヘッダーの検索ボックスと同じく、毎回違う searchId を付けて検索画面へ来るリンク */}
      <Link to="/search?q=hanako" state={{ searchId: 2 }}>
        ヘッダーから検索
      </Link>
      <SearchRoute />
    </>,
    { route },
  )
}

const input = () => screen.getByRole('textbox', { name: 'ユーザーを検索' })
const location = () => screen.getByTestId('location')
const shownUsers = () => screen.queryAllByRole('link', { name: /^ユーザー\d+$/ }).map((a) => a.textContent)

describe('SearchPage：開いたとき', () => {
  test('キーワードなしなら「最近参加したユーザー」を出し、入力欄にフォーカスがある', async () => {
    vi.mocked(searchUsers).mockResolvedValue(aPage(users(1)))
    renderSearch('/search')

    expect(screen.getByRole('heading', { name: '最近参加したユーザー' })).toBeInTheDocument()
    expect(screen.getByText('検索中…')).toBeInTheDocument()
    expect(input()).toHaveFocus()
    expect(input()).toHaveAttribute('maxlength', '50')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1']))
    expect(searchUsers).toHaveBeenCalledWith('')
  })

  test('URL の ?q= を入力欄に入れ、前後の空白を除いたキーワードで検索する', async () => {
    renderSearch('/search?q=%20yama%20')

    expect(input()).toHaveValue(' yama ')
    expect(screen.getByRole('heading', { name: '「yama」の検索結果' })).toBeInTheDocument()
    await vi.waitFor(() => expect(searchUsers).toHaveBeenCalledWith('yama'))
  })

  test.each([
    ['/search', 'まだユーザーがいません'],
    ['/search?q=zzz', '「zzz」に一致するユーザーは見つかりませんでした'],
  ])('%s で0件なら「%s」', async (route, message) => {
    renderSearch(route)

    expect(await screen.findByText(message)).toBeInTheDocument()
  })

  test('検索に失敗 → 再試行 → 検索中 → 結果', async () => {
    const retried = deferred<CursorPage<UserListItem>>()
    vi.mocked(searchUsers).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言')).mockReturnValueOnce(retried.promise)
    renderSearch('/search?q=yama')

    await userEvent.click(await screen.findByRole('button', { name: '再試行' }))
    expect(screen.getByText('検索中…')).toBeInTheDocument()
    retried.resolve(aPage(users(1)))

    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1']))
    expect(searchUsers).toHaveBeenLastCalledWith('yama')
  })

  test('ヘッダーから検索し直したら（searchId が変わったら）、作り直して入力欄もその言葉にする', async () => {
    renderSearch('/search?q=yama')
    await userEvent.clear(input())
    await userEvent.type(input(), '入力途中')

    await userEvent.click(screen.getByRole('link', { name: 'ヘッダーから検索' }))

    expect(input()).toHaveValue('hanako')
    await vi.waitFor(() => expect(searchUsers).toHaveBeenLastCalledWith('hanako'))
  })
})

describe('SearchPage：入力して検索する（境界値：入力が止まってから300ミリ秒）', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    // Testing Library は、偽物の時計かどうかを jest があるかで見分け、あれば待つ間に時計を進める。
    // ないと、内部で待つ setTimeout が偽物の時計のまま進まず、テストが終わらなくなるので、同じ働きの関数を渡す
    vi.stubGlobal('jest', { advanceTimersByTime: (ms: number) => vi.advanceTimersByTime(ms) })
  })

  const user = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

  async function ready(route = '/search') {
    const result = renderSearch(route)
    await act(async () => {})
    vi.mocked(searchUsers).mockClear()
    return result
  }

  test('入力が止まって299ミリ秒ではまだ検索せず、300ミリ秒で URL の ?q= を変えて検索する（1文字ごとには検索しない）', async () => {
    await ready()

    await user().type(input(), 'yama')
    act(() => vi.advanceTimersByTime(299))
    expect(searchUsers).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(1))
    await act(async () => {})

    expect(location()).toHaveTextContent('/search?q=yama')
    expect(searchUsers).toHaveBeenCalledTimes(1)
    expect(searchUsers).toHaveBeenCalledWith('yama')
  })

  test('Enter なら待たずにすぐ検索し、あとから自動の検索は走らない', async () => {
    await ready()

    await user().type(input(), 'yama{Enter}')
    await act(async () => {})
    expect(location()).toHaveTextContent('/search?q=yama')
    act(() => vi.advanceTimersByTime(1000))

    expect(searchUsers).toHaveBeenCalledTimes(1)
  })

  test('空白だけなら、キーワードなし（最近参加したユーザー）にする', async () => {
    await ready('/search?q=yama')

    await user().clear(input())
    await user().type(input(), '   {Enter}')
    await act(async () => {})

    expect(location()).toHaveTextContent(/^\/search$/)
    expect(screen.getByRole('heading', { name: '最近参加したユーザー' })).toBeInTheDocument()
  })

  test('「×」で入力を消して、すぐキーワードなしにする。入力が空なら「×」は出さない', async () => {
    await ready('/search?q=yama')

    await user().click(screen.getByRole('button', { name: '入力をクリア' }))
    await act(async () => {})

    expect(input()).toHaveValue('')
    expect(location()).toHaveTextContent(/^\/search$/)
    expect(screen.queryByRole('button', { name: '入力をクリア' })).not.toBeInTheDocument()
  })

  test('入力の途中でほかの画面へ移ったら、待っている自動の検索をやめる（検索画面へ引き戻さない）', async () => {
    renderWithRouter(
      <>
        <Link to="/users/bobby">ほかの画面へ</Link>
        <Routes>
          <Route path="/search" element={<SearchRoute />} />
          <Route path="*" element={<p>ほかの画面</p>} />
        </Routes>
      </>,
      { route: '/search' },
    )
    await act(async () => {})
    await user().type(input(), 'yama')

    await user().click(screen.getByRole('link', { name: 'ほかの画面へ' }))
    act(() => vi.advanceTimersByTime(300))

    expect(location()).toHaveTextContent('/users/bobby')
  })
})

describe('SearchPage：結果', () => {
  test('前のキーワードの結果があとから届いても、今のキーワードの結果を上書きしない', async () => {
    const old = deferred<CursorPage<UserListItem>>()
    vi.mocked(searchUsers).mockReturnValueOnce(old.promise).mockResolvedValueOnce(aPage(users(2)))
    renderSearch('/search?q=a')

    await userEvent.type(input(), 'b{Enter}')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー2']))
    await act(async () => old.resolve(aPage(users(1))))

    expect(shownUsers()).toEqual(['ユーザー2'])
    expect(screen.getByRole('heading', { name: '「ab」の検索結果' })).toBeInTheDocument()
  })

  test('前のキーワードの失敗があとから届いても、エラーにしない', async () => {
    const old = deferred<CursorPage<UserListItem>>()
    vi.mocked(searchUsers).mockReturnValueOnce(old.promise).mockResolvedValueOnce(aPage(users(2)))
    renderSearch('/search?q=a')

    await userEvent.type(input(), 'b{Enter}')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー2']))
    await act(async () => old.reject(new ApiError(0, 'NETWORK_ERROR', '文言')))

    expect(screen.queryByText('検索に失敗しました。')).not.toBeInTheDocument()
  })

  test('下まで来たら続きを読み込んで後ろにつなげ（重なりは除く）、続きがなくなったら見張りをやめる', async () => {
    vi.mocked(searchUsers).mockResolvedValueOnce(aPage(users(1, 2), 'c1')).mockResolvedValueOnce(aPage(users(2, 3)))
    renderSearch('/search?q=user')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2']))

    FakeIntersectionObserver.scrollToBottom()

    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2', 'ユーザー3']))
    expect(searchUsers).toHaveBeenLastCalledWith('user', 'c1')
    expect(FakeIntersectionObserver.active.size).toBe(0)
  })

  test('続きの読み込みに失敗したら、再試行のボタンで読み込む', async () => {
    vi.mocked(searchUsers)
      .mockResolvedValueOnce(aPage(users(1), 'c1'))
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言'))
      .mockResolvedValueOnce(aPage(users(2)))
    renderSearch('/search?q=user')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1']))

    FakeIntersectionObserver.scrollToBottom()
    expect(await screen.findByText('読み込みに失敗しました')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '再試行' }))

    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2']))
  })

  test('続きを読み込んでいる間にキーワードを変えたら、あとから届いた続きは捨てる', async () => {
    const more = deferred<CursorPage<UserListItem>>()
    vi.mocked(searchUsers)
      .mockResolvedValueOnce(aPage(users(1), 'c1'))
      .mockReturnValueOnce(more.promise)
      .mockResolvedValueOnce(aPage(users(5)))
    renderSearch('/search?q=a')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1']))

    FakeIntersectionObserver.scrollToBottom()
    await userEvent.type(input(), 'b{Enter}')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー5']))
    await act(async () => more.resolve(aPage(users(2))))

    expect(shownUsers()).toEqual(['ユーザー5'])
  })

  test('続きを読み込んでいる間にキーワードを変え、その続きが失敗しても、失敗の表示は出さない', async () => {
    const more = deferred<CursorPage<UserListItem>>()
    vi.mocked(searchUsers)
      .mockResolvedValueOnce(aPage(users(1), 'c1'))
      .mockReturnValueOnce(more.promise)
      .mockResolvedValueOnce(aPage(users(5), 'c5'))
    renderSearch('/search?q=a')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1']))

    FakeIntersectionObserver.scrollToBottom()
    await userEvent.type(input(), 'b{Enter}')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー5']))
    await act(async () => more.reject(new ApiError(0, 'NETWORK_ERROR', '文言')))

    expect(screen.queryByText('読み込みに失敗しました')).not.toBeInTheDocument()
  })

  test('フォローしたら、その行だけ「フォロー中」にする。失敗したらエラーを知らせる', async () => {
    vi.mocked(searchUsers).mockResolvedValue(aPage(users(1, 2)))
    vi.mocked(followUser)
      .mockResolvedValueOnce({ following: true, followerCount: 1 })
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', '文言'))
    renderSearch('/search?q=user')
    await vi.waitFor(() => expect(shownUsers()).toEqual(['ユーザー1', 'ユーザー2']))

    await userEvent.click(screen.getAllByRole('button', { name: 'フォローする' })[0])
    await vi.waitFor(() =>
      expect(screen.getAllByRole('button', { name: /フォロー/ }).map((b) => b.getAttribute('aria-pressed'))).toEqual([
        'true',
        'false',
      ]),
    )

    await userEvent.click(screen.getByRole('button', { name: 'フォローする' }))
    expect(await screen.findByRole('status')).toHaveTextContent('フォローの変更に失敗しました')
  })
})
