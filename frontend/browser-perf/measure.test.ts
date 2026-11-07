import { describe, expect, test } from 'vitest'
import { clsScore, type LayoutShift } from './measure'

const at = (startTime: number, value: number): LayoutShift => ({ startTime, value })

// CLS の数え方（Web Vitals と同じ）：前のずれから 1 秒以内に続くずれを 1 つのまとまりとし（最初から 5 秒まで）、
// まとまりごとの合計の最大を取る。境界値（1 秒・5 秒ちょうど）は、次のまとまりに入れる
describe('clsScore：CLS の数え方', () => {
  test.each<[string, LayoutShift[], number]>([
    ['ずれがなければ 0', [], 0],
    ['1 回だけなら、その値', [at(100, 0.05)], 0.05],
    ['1 秒以内に続くずれは足す', [at(100, 0.05), at(1099, 0.03)], 0.08],
    ['ちょうど 1 秒あいたら別のまとまり（大きいほう）', [at(100, 0.05), at(1100, 0.03)], 0.05],
    ['あとのまとまりのほうが大きければ、そちら', [at(100, 0.01), at(2000, 0.02), at(2500, 0.03)], 0.05],
    ['続いていても、最初から 5 秒で区切る', [0, 900, 1800, 2700, 3600, 4500, 5000].map((t) => at(t, 0.01)), 0.06],
  ])('%s', (_name, shifts, expected) => {
    expect(clsScore(shifts)).toBeCloseTo(expected, 10)
  })
})
