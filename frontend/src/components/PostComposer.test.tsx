// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ApiError } from '../api/client'
import { deferred } from '../test/helpers'
import { PostComposer } from './PostComposer'

// jsdom には画像のプレビュー用の URL を作る機能がないので、テストのたびに偽物を置き、終わったら外す
let objectUrls = 0
beforeEach(() => {
  objectUrls = 0
  URL.createObjectURL = vi.fn(() => `blob:preview-${++objectUrls}`)
  URL.revokeObjectURL = vi.fn()
})
afterEach(() => {
  // @ts-expect-error jsdom にはもともとないものを外す
  delete URL.createObjectURL
  // @ts-expect-error 同上
  delete URL.revokeObjectURL
})

const png = (name = 'a.png') => new File(['x'], name, { type: 'image/png' })
const textbox = () => screen.getByRole('textbox', { name: '本文' })
const submit = () => screen.getByRole('button', { name: /投稿する|投稿中…|保存する|保存中…/ })
const fileInput = () => screen.getByLabelText('画像を選ぶ')

function renderCreate(onSubmit = vi.fn().mockResolvedValue(undefined), onClose = vi.fn()) {
  render(<PostComposer mode="create" onSubmit={onSubmit} onClose={onClose} />)
  return { onSubmit, onClose }
}

describe('PostComposer（作成）：本文の文字数（境界値）', () => {
  test('はじめは空で、投稿ボタンは押せない', () => {
    renderCreate()

    expect(screen.getByRole('dialog', { name: '新しい投稿' })).toBeInTheDocument()
    expect(screen.getByText('0 / 280')).toBeInTheDocument()
    expect(submit()).toBeDisabled()
  })

  test.each([
    ['1文字', 'あ', '1 / 280', true],
    ['280文字', 'あ'.repeat(280), '280 / 280', true],
    ['281文字', 'あ'.repeat(281), '281 / 280', false],
    ['空白と改行だけ', '  \n  ', '0 / 280', false],
    ['絵文字280個', '🎉'.repeat(280), '280 / 280', true],
  ])('%s のとき、文字数の表示と投稿できるかを確かめる', async (_label, content, counter, enabled) => {
    renderCreate()

    await userEvent.click(textbox())
    await userEvent.paste(content)

    expect(screen.getByText(counter)).toBeInTheDocument()
    if (enabled) expect(submit()).toBeEnabled()
    else expect(submit()).toBeDisabled()
  })
})

describe('PostComposer（作成）：画像', () => {
  test('画像だけでも投稿でき、選んだ順に送る', async () => {
    const { onSubmit } = renderCreate()

    await userEvent.upload(fileInput(), [png('1.png'), png('2.png')])
    expect(screen.getByRole('img', { name: '画像1' })).toHaveAttribute('src', 'blob:preview-1')
    await userEvent.click(submit())

    expect(onSubmit).toHaveBeenCalledWith('', [expect.objectContaining({ name: '1.png' }), expect.objectContaining({ name: '2.png' })])
  })

  test('5枚を一度に選んだら4枚だけ足してエラーを出し、4枚になったら「画像を追加」は押せない', async () => {
    renderCreate()

    await userEvent.upload(fileInput(), [png('1.png'), png('2.png'), png('3.png'), png('4.png'), png('5.png')])

    expect(screen.getAllByRole('img')).toHaveLength(4)
    expect(screen.getByRole('alert')).toHaveTextContent('画像は4枚まで添付できます')
    expect(screen.getByRole('button', { name: '🖼 画像を追加' })).toBeDisabled()
  })

  test('選べない画像は足さずにエラーを出し、選べるものは足す', async () => {
    renderCreate()

    await userEvent.upload(fileInput(), [new File(['x'], 'a.webp', { type: 'image/webp' }), png()], { applyAccept: false })

    expect(screen.getAllByRole('img')).toHaveLength(1)
    expect(screen.getByRole('alert')).toHaveTextContent('jpg・png・gif の画像を選択してください')
  })

  test('画像を外すと、一覧から消えてエラーも消える。プレビュー用の URL は解放する', async () => {
    renderCreate()
    await userEvent.upload(fileInput(), [png('1.png'), png('2.png'), png('3.png'), png('4.png'), png('5.png')])

    await userEvent.click(screen.getByRole('button', { name: '画像1を外す' }))

    expect(screen.getAllByRole('img')).toHaveLength(3)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-1')
  })
})

describe('PostComposer：送信（状態遷移）', () => {
  test('送信中は「投稿中…」で押せず、成功したら onSubmit の結果に任せる', async () => {
    const response = deferred<void>()
    const { onSubmit } = renderCreate(vi.fn().mockReturnValue(response.promise))

    await userEvent.type(textbox(), '本文')
    await userEvent.click(submit())

    expect(onSubmit).toHaveBeenCalledWith('本文', [])
    expect(submit()).toHaveTextContent('投稿中…')
    expect(submit()).toBeDisabled()
    response.resolve()
  })

  test.each([
    ['項目ごとのエラー', new ApiError(400, 'VALIDATION_FAILED', '入力内容に誤りがあります', [{ field: 'content', message: '本文は280文字以内で入力してください' }]), '本文は280文字以内で入力してください'],
    ['画像が大きすぎる', new ApiError(413, 'PAYLOAD_TOO_LARGE', '送信するデータが大きすぎます'), '画像のサイズが大きすぎます'],
    ['通信の失敗', new ApiError(0, 'NETWORK_ERROR', '通信に失敗しました。時間をおいてもう一度お試しください'), '通信に失敗しました。時間をおいてもう一度お試しください'],
  ])('失敗（%s）したら、エラーを出して押せるように戻す', async (_label, error, message) => {
    renderCreate(vi.fn().mockRejectedValue(error))

    await userEvent.type(textbox(), '本文')
    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(submit()).toHaveTextContent('投稿する')
    expect(submit()).toBeEnabled()
  })
})

describe('PostComposer：閉じる（デシジョンテーブル：入力中か × 送信中か）', () => {
  test('何も入力していなければ、確認せずに閉じる（× でも Esc でも）', async () => {
    const { onClose } = renderCreate()

    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))
    await userEvent.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(2)
  })

  test('入力中なら「破棄しますか？」と確認し、キャンセルなら残る・破棄するなら閉じる', async () => {
    const { onClose } = renderCreate()
    await userEvent.type(textbox(), '書きかけ')

    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('alertdialog', { name: '入力中の内容は破棄されます。よろしいですか？' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
    expect(onClose).not.toHaveBeenCalled()
    expect(textbox()).toHaveValue('書きかけ')

    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))
    await userEvent.click(screen.getByRole('button', { name: '破棄する' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  test('画像だけを選んだ状態も「入力中」として確認する', async () => {
    const { onClose } = renderCreate()
    await userEvent.upload(fileInput(), [png()])

    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))

    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  test('送信中は閉じない', async () => {
    const { onClose } = renderCreate(vi.fn().mockReturnValue(new Promise(() => {})))
    await userEvent.type(textbox(), '本文')
    await userEvent.click(submit())

    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))

    expect(onClose).not.toHaveBeenCalled()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})

describe('PostComposer（編集）', () => {
  const images = [{ url: 'https://cdn/a.png', sortOrder: 1 }]

  test('今の本文から始め、画像は表示するだけで追加・削除はできない', () => {
    render(<PostComposer mode="edit" initialContent="前の本文" initialImages={images} onSubmit={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByRole('dialog', { name: '投稿を編集' })).toBeInTheDocument()
    expect(textbox()).toHaveValue('前の本文')
    expect(screen.getByRole('img', { name: '画像1' })).toHaveAttribute('src', 'https://cdn/a.png')
    expect(screen.queryByRole('button', { name: '画像1を外す' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '🖼 画像を追加' })).not.toBeInTheDocument()
    expect(submit()).toHaveTextContent('保存する')
  })

  test('画像のある投稿は本文を空にでき、画像のない投稿は空にできない', async () => {
    const { unmount } = render(
      <PostComposer mode="edit" initialContent="前の本文" initialImages={images} onSubmit={vi.fn()} onClose={vi.fn()} />,
    )
    await userEvent.clear(textbox())
    expect(submit()).toBeEnabled()
    unmount()

    render(<PostComposer mode="edit" initialContent="前の本文" onSubmit={vi.fn()} onClose={vi.fn()} />)
    await userEvent.clear(textbox())
    expect(submit()).toBeDisabled()
  })

  test('本文を変えていなければ、確認せずに閉じる', async () => {
    const onClose = vi.fn()
    render(<PostComposer mode="edit" initialContent="前の本文" onSubmit={vi.fn()} onClose={onClose} />)

    await userEvent.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
