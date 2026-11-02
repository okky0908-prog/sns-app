// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { fetchPost } from '../api/client'
import { AuthContext } from '../auth/authContext'
import { reportError } from '../lib/errorReporting'
import { routes } from '../router'
import { anAuth, aPost } from '../test/helpers'

// 投稿詳細の画面がエラーになったことにする（どの画面でも同じ仕組みで受け止める）
vi.mock('./PostDetailPage', () => ({
  PostDetailPage: () => {
    throw new TypeError("Cannot read properties of undefined (reading 'id')")
  },
}))
vi.mock('../lib/errorReporting', () => ({ reportError: vi.fn() }))
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  fetchPost: vi.fn(),
  fetchTimeline: vi.fn(() => new Promise(() => {})),
}))

beforeEach(() => {
  vi.mocked(reportError).mockReset()
  vi.mocked(fetchPost).mockResolvedValue(aPost())
  // React はエラーを受け止めたことを console.error にも出すので、テストの出力では隠す
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

function open(url: string) {
  render(
    <AuthContext.Provider value={anAuth()}>
      <RouterProvider router={createMemoryRouter(routes, { initialEntries: [url] })} />
    </AuthContext.Provider>,
  )
}

describe('ErrorPage：画面の表示中のエラー', () => {
  test('真っ白な画面の代わりに「問題が発生しました」と、再読み込みとホームへの道を出す', async () => {
    open('/posts/10')

    expect(await screen.findByRole('heading', { name: '問題が発生しました' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '再読み込み' })).toBeInTheDocument()
    // 画面の中の状態が壊れているかもしれないので、アプリの中の移動ではなく、ページごと読み込み直すリンクにする
    expect(screen.getByRole('link', { name: 'ホームへ' })).toHaveAttribute('href', '/')
    // React Router のリンク（data-discover が付く）ではない、ふつうのリンク
    expect(screen.getByRole('link', { name: 'ホームへ' })).not.toHaveAttribute('data-discover')
  })

  test('エラーをサーバーに送る（表示中のエラーとして、1回だけ）', async () => {
    open('/posts/10')
    await screen.findByRole('heading', { name: '問題が発生しました' })

    expect(reportError).toHaveBeenCalledTimes(1)
    const [kind, error] = vi.mocked(reportError).mock.calls[0]
    expect(kind).toBe('render')
    expect(error).toBeInstanceOf(TypeError)
  })

  test('エラーにならない画面では出さず、送らない', async () => {
    open('/')

    expect(await screen.findByRole('heading', { name: 'ホーム' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '問題が発生しました' })).not.toBeInTheDocument()
    expect(reportError).not.toHaveBeenCalled()
  })
})
