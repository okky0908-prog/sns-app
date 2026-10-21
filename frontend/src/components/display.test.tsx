// @vitest-environment jsdom
import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { renderWithRouter } from '../test/helpers'
import { Avatar } from './Avatar'
import { FormField } from './FormField'
import { Toast } from './Toast'
import { UserLink } from './UserLink'

afterEach(() => {
  vi.useRealTimers()
})

describe('Toast：画面下に短く出すお知らせ', () => {
  test('文言を出し、2.5秒で消える（2.499秒ではまだ消えない）', () => {
    vi.useFakeTimers()
    const onDone = vi.fn()
    render(<Toast message="投稿しました" onDone={onDone} />)

    expect(screen.getByRole('status')).toHaveTextContent('投稿しました')
    act(() => vi.advanceTimersByTime(2499))
    expect(onDone).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(1))
    expect(onDone).toHaveBeenCalledTimes(1)
  })

  test('消える前に画面から外れたら、あとで onDone を呼ばない', () => {
    vi.useFakeTimers()
    const onDone = vi.fn()
    const { unmount } = render(<Toast message="投稿しました" onDone={onDone} />)

    unmount()
    act(() => vi.advanceTimersByTime(3000))

    expect(onDone).not.toHaveBeenCalled()
  })
})

describe('Avatar：ユーザーのアイコン', () => {
  test('アイコンがあれば画像を出す（読み上げでは名前やリンクで分かるので、画像そのものは読み上げない）', () => {
    const { container } = render(<Avatar displayName="アリス" iconUrl="https://cdn/a.png" size={44} />)

    const img = container.querySelector('img')
    expect(img).toHaveAttribute('src', 'https://cdn/a.png')
    expect(img).toHaveAttribute('alt', '')
    expect(img).toHaveAttribute('width', '44')
  })

  test.each([
    ['アリス', 'ア'],
    ['🎉パーティー', '🎉'], // 絵文字も1文字
    ['alice', 'a'],
    ['', '?'],
  ])('アイコンがなければ、表示名「%s」の頭文字「%s」を出す', (displayName, initial) => {
    const { container } = render(<Avatar displayName={displayName} iconUrl={null} size={44} />)

    expect(container.querySelector('img')).toBeNull()
    expect(container).toHaveTextContent(initial)
  })

  test('画像を読み込めなかったら、壊れた画像の代わりに頭文字を出す', () => {
    const { container } = render(<Avatar displayName="アリス" iconUrl="https://cdn/deleted.png" size={44} />)

    fireEvent.error(container.querySelector('img')!)

    expect(container.querySelector('img')).toBeNull()
    expect(container).toHaveTextContent('ア')
  })
})

describe('UserLink：ユーザーへのリンク', () => {
  test('プロフィールへのリンクで、ユーザー名は URL 用にエスケープする', async () => {
    renderWithRouter(<UserLink username="a_b">@a_b</UserLink>)

    await userEvent.click(screen.getByRole('link', { name: '@a_b' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/users/a_b')
  })
})

describe('FormField：ラベル・入力欄・エラー', () => {
  test('ラベルと入力欄が結び付いていて、入力すると onChange に値を渡す', async () => {
    const onChange = vi.fn()
    render(<FormField id="email" label="メールアドレス" value="" onChange={onChange} type="email" />)

    await userEvent.type(screen.getByLabelText('メールアドレス'), 'a')

    expect(onChange).toHaveBeenCalledWith('a')
    expect(screen.getByLabelText('メールアドレス')).toHaveAttribute('type', 'email')
  })

  test('エラーがなければ、入力欄はエラーの状態ではない', () => {
    render(<FormField id="name" label="表示名" value="" onChange={vi.fn()} />)

    expect(screen.getByLabelText('表示名')).not.toHaveAttribute('aria-invalid')
    expect(screen.queryByText(/文字/)).not.toBeInTheDocument()
  })

  test('エラーがあれば、入力欄の下に出し、読み上げでもエラーの内容が分かる', () => {
    render(<FormField id="name" label="表示名" value="" onChange={vi.fn()} error="表示名は1〜50文字で入力してください" />)

    const input = screen.getByLabelText('表示名')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('表示名は1〜50文字で入力してください')
  })

  test('前に付ける文字（ユーザー名の @）を出す', () => {
    render(<FormField id="username" label="ユーザー名" value="" onChange={vi.fn()} prefix="@" />)

    expect(screen.getByText('@')).toBeInTheDocument()
  })
})
