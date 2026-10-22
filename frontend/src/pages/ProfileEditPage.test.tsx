// @vitest-environment jsdom
import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError, fetchProfile, updateProfile } from '../api/client'
import type { Profile } from '../api/types'
import type { AuthContextValue } from '../auth/authContext'
import { anAuth, aProfile, aUser, deferred, renderWithRouter } from '../test/helpers'
import { ProfileEditPage } from './ProfileEditPage'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  fetchProfile: vi.fn(),
  updateProfile: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(fetchProfile).mockReset().mockResolvedValue(aProfile({ displayName: 'ボブ', bio: 'Javaを勉強中です', iconUrl: null }))
  vi.mocked(updateProfile).mockReset()
  // jsdom には画像のプレビュー用の URL を作る仕組みがないので、偽物を置く
  URL.createObjectURL = vi.fn(() => 'blob:preview')
  URL.revokeObjectURL = vi.fn()
})

afterEach(() => {
  Reflect.deleteProperty(URL, 'createObjectURL')
  Reflect.deleteProperty(URL, 'revokeObjectURL')
})

function renderEdit(auth: AuthContextValue = anAuth({ user: aUser({ username: 'bobby' }) })) {
  const result = renderWithRouter(<ProfileEditPage />, { route: '/settings/profile', auth })
  return { ...result, auth }
}

async function ready(auth?: AuthContextValue) {
  const result = renderEdit(auth)
  await screen.findByRole('heading', { name: 'プロフィールを編集' })
  return result
}

const displayName = () => screen.getByLabelText('表示名')
const bio = () => screen.getByLabelText('自己紹介')
const save = () => screen.getByRole('button', { name: /保存/ })
const cancel = () => screen.getByRole('button', { name: 'キャンセル' })
const location = () => screen.getByTestId('location')
const iconInput = () => screen.getByLabelText('アイコン画像')
/** ファイルを選ぶ（accept で絞り込まずに、選んだものをそのまま渡す） */
const pick = (...files: File[]) => userEvent.setup({ applyAccept: false }).upload(iconInput(), files)
const png = (name = 'icon.png', size = 100) => new File([new Uint8Array(size)], name, { type: 'image/png' })

describe('ProfileEditPage：読み込み（状態遷移）', () => {
  test('読み込み中 → ログイン中のユーザーの今の表示名・自己紹介を入れた入力欄', async () => {
    const response = deferred<Profile>()
    vi.mocked(fetchProfile).mockReturnValue(response.promise)
    renderEdit()

    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    response.resolve(aProfile({ username: 'bobby', displayName: 'ボブ', bio: null }))

    expect(await screen.findByLabelText('表示名')).toHaveValue('ボブ')
    expect(bio()).toHaveValue('') // 自己紹介がなければ空
    expect(fetchProfile).toHaveBeenCalledWith('bobby')
    expect(screen.getByText('@bobby（ユーザー名とメールアドレスは変更できません）')).toBeInTheDocument()
  })

  test('読み込みに失敗したら、文言を出す', async () => {
    vi.mocked(fetchProfile).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '文言'))
    renderEdit()

    expect(await screen.findByText('プロフィールを読み込めませんでした。時間をおいてもう一度お試しください')).toBeInTheDocument()
  })

  test('ログイン中のユーザーがいなければ、読み込まない', () => {
    renderEdit(anAuth({ user: null }))

    expect(fetchProfile).not.toHaveBeenCalled()
    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
  })
})

describe('ProfileEditPage：入力チェック（境界値）', () => {
  test.each([
    ['空', '', '表示名は1〜50文字で入力してください'],
    ['空白だけ', '   ', '表示名は1〜50文字で入力してください'],
    ['1文字', 'あ', null],
    ['50文字', 'あ'.repeat(50), null],
    ['51文字', 'あ'.repeat(51), '表示名は1〜50文字で入力してください'],
    ['絵文字50文字', '🎉'.repeat(50), null], // 絵文字も1文字と数える
  ])('表示名が%s', async (_label, value, error) => {
    vi.mocked(updateProfile).mockReturnValue(new Promise(() => {}))
    await ready()

    await userEvent.clear(displayName())
    if (value) {
      await userEvent.click(displayName())
      await userEvent.paste(value)
    }
    await userEvent.click(save())

    if (error) {
      expect(displayName()).toHaveAccessibleDescription(error)
      expect(updateProfile).not.toHaveBeenCalled()
    } else {
      expect(displayName()).not.toHaveAttribute('aria-invalid')
      expect(updateProfile).toHaveBeenCalled()
    }
  })

  test.each([
    ['160文字', 'あ'.repeat(160), false],
    ['161文字', 'あ'.repeat(161), true],
    ['前後の空白を除いて160文字', `  ${'あ'.repeat(160)}  `, false],
  ])('自己紹介が%s', async (_label, value, invalid) => {
    vi.mocked(updateProfile).mockReturnValue(new Promise(() => {}))
    await ready()

    await userEvent.clear(bio())
    await userEvent.click(bio())
    await userEvent.paste(value)
    await userEvent.click(save())

    if (invalid) {
      expect(screen.getByText('自己紹介は160文字以内で入力してください')).toBeInTheDocument()
      expect(bio()).toHaveAttribute('aria-invalid', 'true')
      expect(updateProfile).not.toHaveBeenCalled()
    } else {
      expect(bio()).not.toHaveAttribute('aria-invalid')
      expect(updateProfile).toHaveBeenCalled()
    }
  })

  test('自己紹介の文字数を「前後の空白を除いた数 / 160」で出す', async () => {
    await ready()

    await userEvent.clear(bio())
    await userEvent.type(bio(), ' あいう ')

    expect(screen.getByText('3 / 160')).toBeInTheDocument()
  })
})

describe('ProfileEditPage：アイコン画像', () => {
  test('画像を選んだらプレビューに出す（保存するまで反映しない）', async () => {
    const { container } = await ready()

    await userEvent.upload(iconInput(), png())

    expect(container.querySelector('img')).toHaveAttribute('src', 'blob:preview')
    expect(updateProfile).not.toHaveBeenCalled()
  })

  test.each([
    ['画像でない', new File(['x'], 'a.txt', { type: 'text/plain' }), 'jpg・png・gif の画像を選択してください'],
    ['5MBを超える', png('big.png', 5 * 1024 * 1024 + 1), '5MB以下の画像を選択してください'],
  ])('%sファイルは選ばずにエラーを出す', async (_label, file, message) => {
    const { container } = await ready()

    await pick(file)

    expect(screen.getByText(message)).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  test('エラーのあとで選べる画像を選んだら、エラーを消す', async () => {
    await ready()
    await pick(new File(['x'], 'a.txt', { type: 'text/plain' }))

    await userEvent.upload(iconInput(), png())

    expect(screen.queryByText('jpg・png・gif の画像を選択してください')).not.toBeInTheDocument()
  })

  test('ファイルを選ばずに閉じたら、何もしない', async () => {
    await ready()

    await pick()

    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  test('別の画像を選び直したら、前のプレビュー用の URL を解放する', async () => {
    vi.mocked(URL.createObjectURL).mockReturnValueOnce('blob:first').mockReturnValueOnce('blob:second')
    await ready()

    await userEvent.upload(iconInput(), png('1.png'))
    await userEvent.upload(iconInput(), png('2.png'))

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:first')
  })

  test('「アイコン画像を変更」のボタンで、ファイルを選ぶ画面を開く', async () => {
    await ready()
    const click = vi.spyOn(iconInput(), 'click')

    await userEvent.click(screen.getByRole('button', { name: 'アイコン画像を変更' }))

    expect(click).toHaveBeenCalled()
  })
})

describe('ProfileEditPage：保存', () => {
  test('前後の空白を除いて送り、送信中は押せない。保存できたら、ヘッダーの表示も変え、お知らせを付けて自分のプロフィールへ', async () => {
    const response = deferred<Profile>()
    vi.mocked(updateProfile).mockReturnValue(response.promise)
    const { auth } = await ready()
    const icon = png()

    await userEvent.clear(displayName())
    await userEvent.type(displayName(), ' 新しい名前 ')
    await userEvent.clear(bio())
    await userEvent.type(bio(), ' 新しい自己紹介 ')
    await userEvent.upload(iconInput(), icon)
    await userEvent.click(save())

    expect(updateProfile).toHaveBeenCalledWith({ displayName: '新しい名前', bio: '新しい自己紹介', icon })
    expect(save()).toHaveTextContent('保存中…')
    expect(save()).toBeDisabled()
    expect(cancel()).toBeDisabled()

    response.resolve(aProfile({ id: 2, username: 'bobby', displayName: '新しい名前', iconUrl: 'https://cdn/new.png' }))
    await vi.waitFor(() => expect(location()).toHaveTextContent('/users/bobby'))
    expect(auth.updateUser).toHaveBeenCalledWith({
      id: 2,
      username: 'bobby',
      displayName: '新しい名前',
      iconUrl: 'https://cdn/new.png',
    })
    expect(screen.getByTestId('location-state')).toHaveTextContent('{"notice":"プロフィールを更新しました"}')
  })

  test('アイコンを選ばなければ、icon は null で送る', async () => {
    vi.mocked(updateProfile).mockReturnValue(new Promise(() => {}))
    await ready()

    await userEvent.click(save())

    expect(updateProfile).toHaveBeenCalledWith({ displayName: 'ボブ', bio: 'Javaを勉強中です', icon: null })
  })

  test('選べなかった画像のエラーが残っていても、保存はできる（エラーは消す）', async () => {
    vi.mocked(updateProfile).mockReturnValue(new Promise(() => {}))
    await ready()
    await pick(new File(['x'], 'a.txt', { type: 'text/plain' }))

    await userEvent.click(save())

    expect(updateProfile).toHaveBeenCalled()
    expect(screen.queryByText('jpg・png・gif の画像を選択してください')).not.toBeInTheDocument()
  })

  test('サーバーの入力エラーは、その項目の下に出す（同じ項目は最初の1つ。知らない項目は出さない）', async () => {
    vi.mocked(updateProfile).mockRejectedValue(
      new ApiError(400, 'VALIDATION_FAILED', '文言', [
        { field: 'displayName', message: 'サーバーの表示名エラー' },
        { field: 'displayName', message: '2つ目のエラー' },
        { field: 'bio', message: 'サーバーの自己紹介エラー' },
        { field: 'icon', message: 'サーバーのアイコンエラー' },
        { field: 'unknown', message: '知らない項目' },
      ]),
    )
    await ready()

    await userEvent.click(save())

    await vi.waitFor(() => expect(displayName()).toHaveAccessibleDescription('サーバーの表示名エラー'))
    expect(screen.getByText('サーバーの自己紹介エラー')).toBeInTheDocument()
    expect(screen.getByText('サーバーのアイコンエラー')).toBeInTheDocument()
    expect(screen.queryByText('2つ目のエラー')).not.toBeInTheDocument()
    expect(screen.queryByText('知らない項目')).not.toBeInTheDocument()
    expect(save()).toBeEnabled()
  })

  test('項目に結び付かない失敗（アイコンのアップロード失敗など）は、入力を残したまま上に出す', async () => {
    vi.mocked(updateProfile).mockRejectedValue(new ApiError(502, 'IMAGE_UPLOAD_FAILED', '画像のアップロードに失敗しました'))
    await ready()
    await userEvent.type(displayName(), 'さん')

    await userEvent.click(save())

    expect(await screen.findByRole('alert')).toHaveTextContent('画像のアップロードに失敗しました')
    expect(displayName()).toHaveValue('ボブさん')
    expect(save()).toBeEnabled()
    expect(location()).toHaveTextContent('/settings/profile')
  })
})

describe('ProfileEditPage：キャンセル（デシジョンテーブル：変更の有無 × 確認の答え）', () => {
  test('何も変えていなければ、確認せずにプロフィールへ戻る', async () => {
    await ready()

    await userEvent.click(cancel())

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(location()).toHaveTextContent('/users/bobby')
  })

  test('一度変えても、もとに戻したなら確認しない', async () => {
    await ready()
    await userEvent.type(displayName(), 'X')
    await userEvent.type(displayName(), '{Backspace}')

    await userEvent.click(cancel())

    expect(location()).toHaveTextContent('/users/bobby')
  })

  test.each([
    ['表示名', async () => userEvent.type(displayName(), 'さん')],
    ['自己紹介', async () => userEvent.type(bio(), '！')],
    ['アイコン', async () => userEvent.upload(iconInput(), png())],
  ])('%sを変えていたら確認し、「破棄する」でプロフィールへ戻る', async (_label, change) => {
    await ready()
    await change()

    await userEvent.click(cancel())
    expect(screen.getByRole('alertdialog')).toHaveTextContent('変更は保存されません。よろしいですか？')
    await userEvent.click(screen.getByRole('button', { name: '破棄する' }))

    expect(location()).toHaveTextContent('/users/bobby')
  })

  test('確認で「キャンセル」なら、入力を残したまま編集を続ける', async () => {
    await ready()
    await userEvent.type(displayName(), 'さん')

    await userEvent.click(cancel())
    await userEvent.click(screen.getAllByRole('button', { name: 'キャンセル' })[1])

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(displayName()).toHaveValue('ボブさん')
    expect(location()).toHaveTextContent('/settings/profile')
  })

  test('読み込みの途中で画面を離れたら、あとで結果が届いても何もしない', async () => {
    const response = deferred<Profile>()
    vi.mocked(fetchProfile).mockReturnValue(response.promise)
    const consoleError = vi.spyOn(console, 'error')
    const { unmount } = renderEdit()

    unmount()
    await act(async () => response.resolve(aProfile()))

    expect(consoleError).not.toHaveBeenCalled()
    consoleError.mockRestore()
  })
})
