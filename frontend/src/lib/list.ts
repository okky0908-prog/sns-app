// 一覧の表示で共通して使う小さな関数

/** すでにある項目と同じ ID のものを除く（続きの読み込みと、手元で足した項目が重ならないように） */
export function withoutDuplicates<T extends { id: number }>(items: T[], existing: T[]): T[] {
  const ids = new Set(existing.map((item) => item.id))
  return items.filter((item) => !ids.has(item.id))
}
