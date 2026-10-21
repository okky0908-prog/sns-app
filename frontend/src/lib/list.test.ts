import { describe, expect, test } from 'vitest'
import { withoutDuplicates } from './list'

const items = (...ids: number[]) => ids.map((id) => ({ id, name: `項目${id}` }))

describe('withoutDuplicates：続きの読み込みで、すでにある項目と同じ ID のものを除く', () => {
  test('すでにある ID の項目だけを除き、残りの並び順は変えない', () => {
    expect(withoutDuplicates(items(5, 4, 3, 2), items(4, 2))).toEqual(items(5, 3))
  })

  test('すでにある項目がなければ、そのまま返す', () => {
    expect(withoutDuplicates(items(3, 2, 1), [])).toEqual(items(3, 2, 1))
  })

  test('すべて重なっていれば空', () => {
    expect(withoutDuplicates(items(2, 1), items(1, 2, 3))).toEqual([])
  })

  test('ID だけで比べ、ほかの項目の違いは見ない（手元で書き換えた項目もすでにあるものとして扱う）', () => {
    const edited = [{ id: 1, name: '手元で書き換えた' }]
    expect(withoutDuplicates(items(1, 2), edited)).toEqual(items(2))
  })
})
