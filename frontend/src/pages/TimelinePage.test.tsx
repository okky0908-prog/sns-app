// @vitest-environment jsdom
import { act, fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, type InitialEntry } from 'react-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, createPost, deletePost, fetchNewPostCount, fetchTimeline, likePost, updatePost, type TimelineTab } from '../api/client'
import type { CursorPage, Post } from '../api/types'
import { aPage, aPost, deferred, FakeIntersectionObserver, renderWithRouter } from '../test/helpers'
import { TimelinePage } from './TimelinePage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  fetchTimeline: vi.fn(),
  fetchNewPostCount: vi.fn(),
  createPost: vi.fn(),
  deletePost: vi.fn(),
  likePost: vi.fn(),
  updatePost: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(fetchTimeline).mockReset()
  vi.mocked(fetchNewPostCount).mockReset()
  vi.mocked(createPost).mockReset()
  vi.mocked(deletePost).mockReset()
  vi.mocked(likePost).mockReset()
  vi.mocked(updatePost).mockReset()
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
  FakeIntersectionObserver.active.clear()
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {}) // jsdom では動かないので、呼ばれたかだけ見る
  // 新しい投稿の確認（60秒ごと）だけ偽物の時計で進める。ほかの待ち時間（入力・お知らせ）は本物のまま
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  Reflect.deleteProperty(document, 'visibilityState')
})

const posts = (...ids: number[]) => ids.map((id) => aPost({ id, content: `投稿${id}` }))

function renderTimeline(tab: TimelineTab = 'following', route: InitialEntry = '/') {
  return renderWithRouter(<TimelinePage tab={tab} />, { route })
}

/** 表示している投稿の本文（上から順） */
const shown = () =>
  within(screen.getByRole('region', { name: '投稿一覧' }))
    .queryAllByText(/^投稿\d+$/)
    .map((p) => p.textContent)

/** 60秒たって、新しい投稿の件数を確認する */
async function waitCheckInterval() {
  act(() => vi.advanceTimersByTime(60_000))
  await act(async () => {}) // 件数の結果を画面に反映させる
}

const newPostsButton = () => screen.queryByRole('button', { name: /件の新しい投稿/ })
const newPostsDialog = () => screen.queryByRole('dialog', { name: '新しい投稿があります' })

describe('TimelinePage：読み込み（状態遷移）', () => {
  test.each<TimelineTab>(['following', 'all'])('読み込み中 → 新しい順の一覧（%s タブ）。続きがなければ「これ以上の投稿はありません」', async (tab) => {
    const response = deferred<CursorPage<Post>>()
    vi.mocked(fetchTimeline).mockReturnValue(response.promise)
    renderTimeline(tab)

    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '投稿一覧' })).toHaveAttribute('aria-busy', 'true')
    response.resolve(aPage(posts(3, 2, 1)))

    await vi.waitFor(() => expect(shown()).toEqual(['投稿3', '投稿2', '投稿1']))
    expect(fetchTimeline).toHaveBeenCalledWith(tab)
    expect(screen.getByText('これ以上の投稿はありません')).toBeInTheDocument()
  })

  test('フォロー中のタブで0件なら、ユーザー検索へ案内する', async () => {
    vi.mocked(fetchTimeline).mockResolvedValue(aPage([]))
    renderTimeline('following')

    expect(await screen.findByRole('link', { name: 'ユーザーを検索' })).toHaveAttribute('href', '/search')
    expect(screen.queryByText('これ以上の投稿はありません')).not.toBeInTheDocument()
  })

  test('全体のタブで0件なら、最初の投稿をすすめる', async () => {
    vi.mocked(fetchTimeline).mockResolvedValue(aPage([]))
    renderTimeline('all')

    expect(await screen.findByText('まだ誰も投稿していません。最初の投稿をしてみましょう')).toBeInTheDocument()
  })

  test('読み込みに失敗 → 再試行 → 読み込み中 → 一覧', async () => {
    const retried = deferred<CursorPage<Post>>()
    vi.mocked(fetchTimeline).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言')).mockReturnValueOnce(retried.promise)
    renderTimeline()

    await userEvent.click(await screen.findByRole('button', { name: '再試行' }))
    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    expect(screen.queryByText('タイムラインを読み込めませんでした。')).not.toBeInTheDocument()
    retried.resolve(aPage(posts(1)))

    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))
  })
})

describe('TimelinePage：無限スクロール', () => {
  test('下まで来たら続きを読み込んで後ろにつなげ（重なりは除く）、続きがなくなったら終わりの表示', async () => {
    vi.mocked(fetchTimeline)
      .mockResolvedValueOnce(aPage(posts(5, 4), 'c1'))
      .mockResolvedValueOnce(aPage(posts(4, 3), null))
    renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿5', '投稿4']))
    expect(screen.queryByText('これ以上の投稿はありません')).not.toBeInTheDocument()

    FakeIntersectionObserver.scrollToBottom()

    await vi.waitFor(() => expect(shown()).toEqual(['投稿5', '投稿4', '投稿3']))
    expect(fetchTimeline).toHaveBeenLastCalledWith('following', 'c1')
    expect(screen.getByText('これ以上の投稿はありません')).toBeInTheDocument()
    expect(screen.queryByTestId('timeline-sentinel')).not.toBeInTheDocument()
  })

  test('続きの読み込みに失敗したら、自動では読み込まず、再試行のボタンで読み込む', async () => {
    vi.mocked(fetchTimeline)
      .mockResolvedValueOnce(aPage(posts(5), 'c1'))
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '文言'))
      .mockResolvedValueOnce(aPage(posts(4)))
    renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿5']))

    FakeIntersectionObserver.scrollToBottom()
    expect(await screen.findByText('読み込みに失敗しました')).toBeInTheDocument()
    // 失敗している間は見張らない（何度も自動で読み込まない）
    expect(FakeIntersectionObserver.active.size).toBe(0)

    await userEvent.click(screen.getByRole('button', { name: '再試行' }))

    await vi.waitFor(() => expect(shown()).toEqual(['投稿5', '投稿4']))
  })

  test('続きを読み込んでいる間に最新を取り直したら、あとから届いた続きは捨てる', async () => {
    const more = deferred<CursorPage<Post>>()
    vi.mocked(fetchTimeline)
      .mockResolvedValueOnce(aPage(posts(5), 'c1'))
      .mockReturnValueOnce(more.promise)
      .mockResolvedValueOnce(aPage(posts(6, 5)))
    renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿5']))

    FakeIntersectionObserver.scrollToBottom() // 続きを読み込み始める
    await userEvent.click(screen.getByRole('link', { name: 'フォロー中' })) // 表示中のタブ → 最新を取り直す
    await vi.waitFor(() => expect(shown()).toEqual(['投稿6', '投稿5']))
    await act(async () => more.resolve(aPage(posts(1))))

    expect(shown()).toEqual(['投稿6', '投稿5'])
  })
})

describe('TimelinePage：最新の取り直し', () => {
  test('表示中のタブをもう一度押したら、URL は変えずに取り直して一番上へ', async () => {
    vi.mocked(fetchTimeline).mockResolvedValueOnce(aPage(posts(1))).mockResolvedValueOnce(aPage(posts(2, 1)))
    renderTimeline('following')
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    await userEvent.click(screen.getByRole('link', { name: 'フォロー中' }))

    await vi.waitFor(() => expect(shown()).toEqual(['投稿2', '投稿1']))
    expect(window.scrollTo).toHaveBeenCalledWith(0, 0)
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
  })

  test('ほかのタブを押したら、そのタブの URL へ移動する（取り直しはしない）', async () => {
    vi.mocked(fetchTimeline).mockResolvedValue(aPage(posts(1)))
    renderTimeline('following')
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    await userEvent.click(screen.getByRole('link', { name: '全体' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/all')
    expect(fetchTimeline).toHaveBeenCalledTimes(1)
  })

  test('続けて取り直したとき、先に始めた取得の結果があとから届いても使わない', async () => {
    const first = deferred<CursorPage<Post>>()
    vi.mocked(fetchTimeline)
      .mockResolvedValueOnce(aPage(posts(1)))
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(aPage(posts(3, 2, 1)))
    renderTimeline('following')
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    await userEvent.click(screen.getByRole('link', { name: 'フォロー中' }))
    await userEvent.click(screen.getByRole('link', { name: 'フォロー中' }))
    await vi.waitFor(() => expect(shown()).toEqual(['投稿3', '投稿2', '投稿1']))
    await act(async () => first.resolve(aPage(posts(2, 1))))

    expect(shown()).toEqual(['投稿3', '投稿2', '投稿1'])
  })

  test('ヘッダーの「ホーム」が押された（state に refresh を付けて来た）ら取り直し、state は消す', async () => {
    vi.mocked(fetchTimeline).mockResolvedValueOnce(aPage(posts(1))).mockResolvedValueOnce(aPage(posts(2, 1)))
    // AppLayout の「ホーム」と同じリンクを、タイムラインと一緒に置く
    renderWithRouter(
      <>
        <Link to="/" state={{ refresh: true }}>
          ヘッダーのホーム
        </Link>
        <TimelinePage tab="following" />
      </>,
    )
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    await userEvent.click(screen.getByRole('link', { name: 'ヘッダーのホーム' }))

    await vi.waitFor(() => expect(shown()).toEqual(['投稿2', '投稿1']))
    expect(screen.getByTestId('location-state')).toHaveTextContent('null')
  })

  test('読み込み中に refresh 付きで来たら、最初の読み込みで足りるので取り直さない（state は消す）', async () => {
    vi.mocked(fetchTimeline).mockResolvedValue(aPage(posts(1)))
    renderTimeline('following', { pathname: '/', state: { refresh: true } })

    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))
    expect(screen.getByTestId('location-state')).toHaveTextContent('null')
    expect(fetchTimeline).toHaveBeenCalledTimes(1)
  })
})

describe('TimelinePage：新しい投稿のお知らせ（状態遷移）', () => {
  async function ready(tab: TimelineTab = 'following') {
    vi.mocked(fetchTimeline).mockResolvedValue(aPage(posts(7, 6)))
    renderTimeline(tab)
    // vi.waitFor は偽物の時計を少しずつ進めながら待つので、ここでは使わない（60秒の境目がずれる）
    await act(async () => {})
    expect(shown()).toEqual(['投稿7', '投稿6'])
  }

  test('59.999秒ではまだ確認せず、60秒で「一番新しい投稿より後」の件数を確認する', async () => {
    vi.mocked(fetchNewPostCount).mockResolvedValue(0)
    await ready('all')

    act(() => vi.advanceTimersByTime(59_999))
    expect(fetchNewPostCount).not.toHaveBeenCalled()
    await waitCheckInterval()

    expect(fetchNewPostCount).toHaveBeenCalledWith('all', 7)
  })

  test('0件なら何も出さない', async () => {
    vi.mocked(fetchNewPostCount).mockResolvedValue(0)
    await ready()

    await waitCheckInterval()

    expect(newPostsButton()).not.toBeInTheDocument()
    expect(newPostsDialog()).not.toBeInTheDocument()
  })

  test('件数があればボタンとモーダルで知らせる。「あとで」ならモーダルだけ閉じ、同じ件数ではもう出さない。増えたらまた出す', async () => {
    vi.mocked(fetchNewPostCount).mockResolvedValueOnce(2).mockResolvedValueOnce(2).mockResolvedValueOnce(3)
    await ready()

    await waitCheckInterval()
    expect(newPostsButton()).toHaveTextContent('↑ 2件の新しい投稿')
    expect(newPostsDialog()).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'あとで' }))
    expect(newPostsDialog()).not.toBeInTheDocument()
    expect(newPostsButton()).toBeInTheDocument()

    await waitCheckInterval() // 2件のまま
    expect(newPostsDialog()).not.toBeInTheDocument()

    await waitCheckInterval() // 3件に増えた
    expect(newPostsDialog()).toBeInTheDocument()
    expect(newPostsButton()).toHaveTextContent('↑ 3件の新しい投稿')
  })

  test.each([
    [99, '↑ 99件の新しい投稿'],
    [100, '↑ 99+件の新しい投稿'],
  ])('%i件なら、ボタンは「%s」', async (count, label) => {
    vi.mocked(fetchNewPostCount).mockResolvedValue(count)
    await ready()

    await waitCheckInterval()

    expect(newPostsButton()).toHaveTextContent(label)
  })

  test('「最新の投稿を見る」で、取り直しを待たずにお知らせを消し、最新を取り直す', async () => {
    vi.mocked(fetchNewPostCount).mockResolvedValue(1)
    await ready()
    await waitCheckInterval()
    const refetched = deferred<CursorPage<Post>>()
    vi.mocked(fetchTimeline).mockReturnValueOnce(refetched.promise)

    await userEvent.click(screen.getByRole('button', { name: '最新の投稿を見る' }))
    expect(newPostsDialog()).not.toBeInTheDocument()
    expect(newPostsButton()).not.toBeInTheDocument()
    refetched.resolve(aPage(posts(8, 7, 6)))

    await vi.waitFor(() => expect(shown()).toEqual(['投稿8', '投稿7', '投稿6']))
    await waitCheckInterval()
    expect(fetchNewPostCount).toHaveBeenLastCalledWith('following', 8) // 取り直した一番新しい投稿より後を数える
  })

  test('「↑ N件の新しい投稿」のボタンでも取り直す', async () => {
    vi.mocked(fetchNewPostCount).mockResolvedValue(1)
    await ready()
    await waitCheckInterval()
    await userEvent.click(screen.getByRole('button', { name: 'あとで' }))
    vi.mocked(fetchTimeline).mockResolvedValueOnce(aPage(posts(8, 7, 6)))

    await userEvent.click(newPostsButton()!)

    await vi.waitFor(() => expect(shown()).toEqual(['投稿8', '投稿7', '投稿6']))
    expect(newPostsButton()).not.toBeInTheDocument()
  })

  test('件数の確認中に最新を取り直したら、あとから届いた件数は使わない', async () => {
    const count = deferred<number>()
    vi.mocked(fetchNewPostCount).mockReturnValue(count.promise)
    await ready()
    act(() => vi.advanceTimersByTime(60_000)) // 確認を始める（まだ返ってこない）

    await userEvent.click(screen.getByRole('link', { name: 'フォロー中' }))
    await vi.waitFor(() => expect(fetchTimeline).toHaveBeenCalledTimes(2))
    await act(async () => count.resolve(5))

    expect(newPostsButton()).not.toBeInTheDocument()
  })

  test('確認に失敗しても、画面には何も出さない', async () => {
    vi.mocked(fetchNewPostCount).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '文言'))
    await ready()

    await waitCheckInterval()

    expect(newPostsButton()).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  test('ブラウザのタブが裏にある間は確認せず、表に戻ったらすぐ確認する', async () => {
    vi.mocked(fetchNewPostCount).mockResolvedValue(4)
    await ready()

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    await waitCheckInterval()
    expect(fetchNewPostCount).not.toHaveBeenCalled()

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    await act(async () => fireEvent(document, new Event('visibilitychange')))

    expect(fetchNewPostCount).toHaveBeenCalledTimes(1)
    expect(newPostsButton()).toHaveTextContent('↑ 4件の新しい投稿')
  })

  test('読み込みに失敗している間は確認しない', async () => {
    vi.mocked(fetchTimeline).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '文言'))
    renderTimeline()
    await screen.findByRole('button', { name: '再試行' })

    await waitCheckInterval()

    expect(fetchNewPostCount).not.toHaveBeenCalled()
  })

  test('画面を離れたら確認をやめる', async () => {
    vi.mocked(fetchNewPostCount).mockResolvedValue(0)
    vi.mocked(fetchTimeline).mockResolvedValue(aPage(posts(1)))
    const { unmount } = renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    unmount()
    await waitCheckInterval()
    fireEvent(document, new Event('visibilitychange'))

    expect(fetchNewPostCount).not.toHaveBeenCalled()
  })

  test('投稿のモーダルなど、ほかの表示を開いている間はお知らせのモーダルを出さず、閉じたら出す', async () => {
    vi.mocked(fetchNewPostCount).mockResolvedValue(2)
    await ready()
    await userEvent.click(screen.getByRole('button', { name: '✏ 投稿する' }))

    await waitCheckInterval()
    expect(newPostsButton()).toBeInTheDocument()
    expect(newPostsDialog()).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))
    expect(newPostsDialog()).toBeInTheDocument()
  })
})

describe('TimelinePage：投稿・いいね・削除', () => {
  beforeEach(() => {
    vi.mocked(fetchTimeline).mockResolvedValue(aPage([aPost({ id: 1, content: '投稿1', mine: true, likeCount: 0 })]))
  })

  test('投稿したらモーダルを閉じ、一覧の先頭に足して一番上へ。「投稿しました」と知らせる', async () => {
    vi.mocked(createPost).mockResolvedValue(aPost({ id: 2, content: '投稿2', mine: true }))
    renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    await userEvent.click(screen.getByRole('button', { name: '✏ 投稿する' }))
    await userEvent.type(screen.getByRole('textbox', { name: '本文' }), '投稿2')
    await userEvent.click(within(screen.getByRole('dialog', { name: '新しい投稿' })).getByRole('button', { name: '投稿する' }))

    await vi.waitFor(() => expect(shown()).toEqual(['投稿2', '投稿1']))
    expect(createPost).toHaveBeenCalledWith('投稿2', [])
    expect(screen.queryByRole('dialog', { name: '新しい投稿' })).not.toBeInTheDocument()
    expect(window.scrollTo).toHaveBeenCalledWith(0, 0)
    expect(screen.getByRole('status')).toHaveTextContent('投稿しました')
  })

  test('いいねしたら、その投稿の表示を変える', async () => {
    vi.mocked(likePost).mockResolvedValue({ likeCount: 4, likedByMe: true })
    renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    await userEvent.click(screen.getByRole('button', { name: 'いいね' }))

    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'いいねを取り消す' })).toHaveTextContent('♥4'))
  })

  test('いいねに失敗したら、エラーを知らせる', async () => {
    vi.mocked(likePost).mockRejectedValue(new ApiError(404, 'POST_NOT_FOUND', '文言'))
    renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    await userEvent.click(screen.getByRole('button', { name: 'いいね' }))

    expect(await screen.findByRole('status')).toHaveTextContent('この投稿は削除されています')
  })

  test('自分の投稿を編集したら、一覧のその投稿だけ新しい本文にする', async () => {
    vi.mocked(updatePost).mockResolvedValue(aPost({ id: 1, content: '投稿100', mine: true }))
    vi.mocked(fetchTimeline).mockResolvedValue(
      aPage([aPost({ id: 1, content: '投稿1', mine: true }), aPost({ id: 2, content: '投稿2', mine: true })]),
    )
    renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1', '投稿2']))

    await userEvent.click(screen.getAllByRole('button', { name: 'メニュー' })[0])
    await userEvent.click(screen.getByRole('menuitem', { name: '編集' }))
    await userEvent.clear(screen.getByRole('textbox', { name: '本文' }))
    await userEvent.type(screen.getByRole('textbox', { name: '本文' }), '投稿100')
    await userEvent.click(screen.getByRole('button', { name: '保存する' }))

    await vi.waitFor(() => expect(shown()).toEqual(['投稿100', '投稿2']))
    expect(updatePost).toHaveBeenCalledWith(1, '投稿100')
  })

  test('削除したら一覧から消す', async () => {
    vi.mocked(deletePost).mockResolvedValue()
    renderTimeline()
    await vi.waitFor(() => expect(shown()).toEqual(['投稿1']))

    await userEvent.click(screen.getByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: '削除する' }))

    await vi.waitFor(() => expect(shown()).toEqual([]))
  })

  test.each([
    [{ notice: '投稿を削除しました' }, '投稿を削除しました'],
    [{ notice: 'この投稿はすでに削除されています', noticeError: true }, 'この投稿はすでに削除されています'],
  ])('投稿詳細から戻ってきたときのお知らせ（%o）を出し、state は消す', async (state, message) => {
    renderTimeline('following', { pathname: '/', state })

    expect(await screen.findByRole('status')).toHaveTextContent(message)
    expect(screen.getByTestId('location-state')).toHaveTextContent('null')
  })
})
