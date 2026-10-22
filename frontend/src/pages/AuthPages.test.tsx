// @vitest-environment jsdom
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { ApiError } from '../api/client'
import { anAuth, deferred, renderWithRouter } from '../test/helpers'
import { LoginPage } from './LoginPage'
import { SignupPage } from './SignupPage'

const location = () => screen.getByTestId('location')

describe('LoginPage：ログイン', () => {
  function renderLogin(login = vi.fn().mockResolvedValue(undefined)) {
    renderWithRouter(<LoginPage />, { route: '/login', auth: anAuth({ user: null, login }) })
    return login
  }

  const email = () => screen.getByLabelText('メールアドレス')
  const password = () => screen.getByLabelText('パスワード')
  const submit = () => screen.getByRole('button', { name: /ログイン/ })

  test('開いたらメールアドレスの欄にフォーカスがあり、新規登録へのリンクがある', () => {
    renderLogin()

    expect(email()).toHaveFocus()
    expect(screen.getByRole('link', { name: '新規登録はこちら' })).toHaveAttribute('href', '/signup')
  })

  test.each([
    ['どちらも空', '', '', ['メールアドレスを入力してください', 'パスワードを入力してください']],
    ['メールアドレスが空白だけ', '   ', 'password', ['メールアドレスを入力してください']],
    ['パスワードが空', 'a@example.com', '', ['パスワードを入力してください']],
  ])('%sなら送らずにエラーを出す', async (_label, emailValue, passwordValue, messages) => {
    const login = renderLogin()

    if (emailValue) await userEvent.type(email(), emailValue)
    if (passwordValue) await userEvent.type(password(), passwordValue)
    await userEvent.click(submit())

    for (const message of messages) expect(screen.getByText(message)).toBeInTheDocument()
    expect(login).not.toHaveBeenCalled()
  })

  test('前後の空白を除いたメールアドレスで送り、送信中は押せない。ログインできたらホームへ', async () => {
    const response = deferred<void>()
    const login = renderLogin(vi.fn().mockReturnValue(response.promise))

    await userEvent.type(email(), '  a@example.com ')
    await userEvent.type(password(), ' pass word ')
    await userEvent.click(submit())

    expect(login).toHaveBeenCalledWith({ email: 'a@example.com', password: ' pass word ' }) // パスワードは空白も含めてそのまま
    expect(submit()).toHaveTextContent('ログイン中…')
    expect(submit()).toBeDisabled()

    response.resolve()
    await vi.waitFor(() => expect(location()).toHaveTextContent(/^\/$/))
  })

  test('メールアドレスかパスワードが違うなら、サーバーの文言を出し、パスワードだけ空にしてそこへフォーカス', async () => {
    renderLogin(vi.fn().mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'メールアドレスまたはパスワードが正しくありません')))

    await userEvent.type(email(), 'a@example.com')
    await userEvent.type(password(), 'wrongpass')
    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('メールアドレスまたはパスワードが正しくありません')
    expect(email()).toHaveValue('a@example.com')
    expect(password()).toHaveValue('')
    expect(password()).toHaveFocus()
    expect(submit()).toBeEnabled()
    expect(location()).toHaveTextContent('/login')
  })

  test('それ以外の失敗（通信エラーなど）は、入力を残したまま文言を出す', async () => {
    renderLogin(vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await userEvent.type(email(), 'a@example.com')
    await userEvent.type(password(), 'password')
    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('通信に失敗しました。時間をおいてもう一度お試しください')
    expect(password()).toHaveValue('password')
  })

  test('もう一度送ったら、前のエラーは消す', async () => {
    renderLogin(vi.fn().mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '通信エラー')).mockReturnValueOnce(new Promise(() => {})))

    await userEvent.type(email(), 'a@example.com')
    await userEvent.type(password(), 'password')
    await userEvent.click(submit())
    await screen.findByRole('alert')
    await userEvent.click(submit())

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('SignupPage：新規登録', () => {
  function renderSignup(signup = vi.fn().mockResolvedValue(undefined)) {
    renderWithRouter(<SignupPage />, { route: '/signup', auth: anAuth({ user: null, signup }) })
    return signup
  }

  const submit = () => screen.getByRole('button', { name: /登録/ })

  async function fill({
    username = 'alice_01',
    displayName = 'アリス',
    email = 'alice@example.com',
    password = 'password123',
    passwordConfirm = undefined as string | undefined,
  } = {}) {
    passwordConfirm ??= password
    if (username) await userEvent.type(screen.getByLabelText('ユーザー名'), username)
    if (displayName) await userEvent.type(screen.getByLabelText('表示名'), displayName)
    if (email) await userEvent.type(screen.getByLabelText('メールアドレス'), email)
    if (password) await userEvent.type(screen.getByLabelText('パスワード（8文字以上）'), password)
    if (passwordConfirm) await userEvent.type(screen.getByLabelText('パスワード（確認）'), passwordConfirm)
  }

  test('開いたらユーザー名の欄にフォーカスがあり、ログインへのリンクがある。ユーザー名は15文字までしか入らない', async () => {
    renderSignup()

    const username = screen.getByLabelText('ユーザー名')
    expect(username).toHaveFocus()
    expect(username).toHaveAttribute('maxlength', '15')
    expect(screen.getByRole('link', { name: 'ログインはこちら' })).toHaveAttribute('href', '/login')
  })

  test('入力チェックに引っかかったら、送らずに項目ごとにエラーを出す', async () => {
    const signup = renderSignup()

    await fill({ username: 'ab', password: 'short', passwordConfirm: 'other' })
    await userEvent.click(submit())

    expect(screen.getByLabelText('ユーザー名')).toHaveAccessibleDescription('ユーザー名は半角英数字と_で4〜15文字で入力してください')
    expect(screen.getByLabelText('パスワード（8文字以上）')).toHaveAccessibleDescription('パスワードは8文字以上で入力してください')
    expect(screen.getByLabelText('パスワード（確認）')).toHaveAccessibleDescription('パスワードが一致しません')
    expect(screen.getByLabelText('表示名')).not.toHaveAttribute('aria-invalid')
    expect(signup).not.toHaveBeenCalled()
  })

  test('表示名・メールアドレスは前後の空白を除いて送り、確認用のパスワードは送らない。登録できたらホームへ', async () => {
    const response = deferred<void>()
    const signup = renderSignup(vi.fn().mockReturnValue(response.promise))

    await fill({ displayName: ' アリス ', email: ' alice@example.com ' })
    await userEvent.click(submit())

    expect(signup).toHaveBeenCalledWith({
      username: 'alice_01',
      displayName: 'アリス',
      email: 'alice@example.com',
      password: 'password123',
    })
    expect(submit()).toHaveTextContent('登録中…')
    expect(submit()).toBeDisabled()
    response.resolve()
    await vi.waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/))
  })

  test('サーバーの入力エラー・重複は、その項目の下に出す（同じ項目は最初の1つ。知らない項目は出さない）', async () => {
    renderSignup(
      vi.fn().mockRejectedValue(
        new ApiError(409, 'ALREADY_REGISTERED', '文言', [
          { field: 'username', message: 'このユーザー名は既に使われています' },
          { field: 'username', message: '2つ目のエラー' },
          { field: 'email', message: 'このメールアドレスは既に登録されています' },
          { field: 'unknown', message: '知らない項目' },
        ]),
      ),
    )

    await fill()
    await userEvent.click(submit())

    await vi.waitFor(() =>
      expect(screen.getByLabelText('ユーザー名')).toHaveAccessibleDescription('このユーザー名は既に使われています'),
    )
    expect(screen.getByLabelText('メールアドレス')).toHaveAccessibleDescription('このメールアドレスは既に登録されています')
    expect(screen.queryByText('2つ目のエラー')).not.toBeInTheDocument()
    expect(screen.queryByText('知らない項目')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(submit()).toBeEnabled()
  })

  test('項目に結び付かない失敗は、上にまとめて出す', async () => {
    renderSignup(vi.fn().mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'サーバーでエラーが発生しました')))

    await fill()
    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('サーバーでエラーが発生しました')
  })
})
