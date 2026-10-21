// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'
import { ImageViewer } from './ImageViewer'
import { NewPostsDialog } from './NewPostsDialog'
import { PostImages } from './PostImages'

const images = (count: number) =>
  Array.from({ length: count }, (_, i) => ({ url: `https://cdn/${i + 1}.png`, sortOrder: i + 1 }))

describe('ConfirmDialog：削除などの前の確認', () => {
  test('文言を出し、確定のボタンにフォーカスを当てる（Enter でそのまま確定できる）', () => {
    render(<ConfirmDialog message="この投稿を削除しますか？" confirmLabel="削除する" onConfirm={vi.fn()} onCancel={vi.fn()} />)

    expect(screen.getByRole('alertdialog', { name: 'この投稿を削除しますか？' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '削除する' })).toHaveFocus()
  })

  test('確定・キャンセル・Esc で、それぞれの処理を呼ぶ', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(<ConfirmDialog message="確認" confirmLabel="削除する" onConfirm={onConfirm} onCancel={onCancel} />)

    await userEvent.click(screen.getByRole('button', { name: '削除する' }))
    await userEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
    await userEvent.keyboard('{Escape}')

    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onCancel).toHaveBeenCalledTimes(2)
  })

  test('Esc 以外のキーでは閉じない', async () => {
    const onCancel = vi.fn()
    render(<ConfirmDialog message="確認" confirmLabel="削除する" onConfirm={vi.fn()} onCancel={onCancel} />)

    await userEvent.keyboard('a{Tab}')

    expect(onCancel).not.toHaveBeenCalled()
  })
})

describe('NewPostsDialog：新しい投稿のお知らせ（F-24）', () => {
  test.each([
    [1, '1件'],
    [99, '99件'],
    [100, '99+件'], // 99件を超えたら「99+」
  ])('%i件なら「%s」と出す', (count, label) => {
    render(<NewPostsDialog count={count} onShow={vi.fn()} onLater={vi.fn()} />)

    expect(screen.getByText(`${label}の新しい投稿があります。最新の投稿を表示しますか？`)).toBeInTheDocument()
  })

  test('「最新の投稿を見る」にフォーカスを当て、ボタンと Esc でそれぞれの処理を呼ぶ', async () => {
    const onShow = vi.fn()
    const onLater = vi.fn()
    render(<NewPostsDialog count={3} onShow={onShow} onLater={onLater} />)

    expect(screen.getByRole('dialog', { name: '新しい投稿があります' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '最新の投稿を見る' })).toHaveFocus()
    await userEvent.click(screen.getByRole('button', { name: '最新の投稿を見る' }))
    await userEvent.click(screen.getByRole('button', { name: 'あとで' }))
    await userEvent.keyboard('{Escape}')

    expect(onShow).toHaveBeenCalledTimes(1)
    expect(onLater).toHaveBeenCalledTimes(2)
  })

  test('Esc 以外のキーでは閉じない', async () => {
    const onLater = vi.fn()
    render(<NewPostsDialog count={3} onShow={vi.fn()} onLater={onLater} />)

    await userEvent.keyboard('a')

    expect(onLater).not.toHaveBeenCalled()
  })
})

describe('ImageViewer：画像の拡大表示', () => {
  const shown = () => screen.getByRole('img').getAttribute('src')

  test('押した画像から表示し、閉じるボタンにフォーカスを当てる', () => {
    render(<ImageViewer images={images(3)} startIndex={1} onClose={vi.fn()} />)

    expect(shown()).toBe('https://cdn/2.png')
    expect(screen.getByRole('dialog', { name: '画像 2 / 3' })).toBeInTheDocument()
    expect(screen.getByText('2 / 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '閉じる' })).toHaveFocus()
  })

  test('次・前のボタンと左右の矢印キーで移動し、端では反対の端へ回る', async () => {
    render(<ImageViewer images={images(3)} startIndex={2} onClose={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: '次の画像' }))
    expect(shown()).toBe('https://cdn/1.png') // 最後 → 最初
    await userEvent.click(screen.getByRole('button', { name: '前の画像' }))
    expect(shown()).toBe('https://cdn/3.png') // 最初 → 最後
    await userEvent.keyboard('{ArrowRight}')
    expect(shown()).toBe('https://cdn/1.png')
    await userEvent.keyboard('{ArrowLeft}')
    expect(shown()).toBe('https://cdn/3.png')
  })

  test('1枚だけなら、前後の移動ボタンと位置を出さず、矢印キーでも動かない', async () => {
    render(<ImageViewer images={images(1)} startIndex={0} onClose={vi.fn()} />)

    expect(screen.queryByRole('button', { name: '次の画像' })).not.toBeInTheDocument()
    expect(screen.queryByText('1 / 1')).not.toBeInTheDocument()
    await userEvent.keyboard('{ArrowRight}')
    expect(shown()).toBe('https://cdn/1.png')
  })

  test('閉じるボタン・Esc・画像の外側のクリックで閉じ、画像そのもののクリックでは閉じない', async () => {
    const onClose = vi.fn()
    const { container } = render(<ImageViewer images={images(2)} startIndex={0} onClose={onClose} />)

    await userEvent.click(screen.getByRole('img'))
    expect(onClose).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))
    await userEvent.keyboard('{Escape}')
    await userEvent.click(container.firstElementChild as HTMLElement) // 画像の外側（暗い部分）
    expect(onClose).toHaveBeenCalledTimes(3)
  })
})

describe('PostImages：投稿カードの画像', () => {
  test('画像がなければ何も出さない', () => {
    const { container } = render(<PostImages images={[]} />)

    expect(container).toBeEmptyDOMElement()
  })

  test('画像ごとに拡大のボタンを出し、押すとその画像から拡大表示する。閉じたらボタンにフォーカスを戻す', async () => {
    render(<PostImages images={images(4)} />)

    const third = screen.getByRole('button', { name: '画像 3 / 4 を拡大' })
    expect(screen.getAllByRole('button', { name: /を拡大$/ })).toHaveLength(4)
    await userEvent.click(third)
    expect(screen.getByRole('dialog', { name: '画像 3 / 4' })).toBeInTheDocument()

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(third).toHaveFocus()
  })
})
