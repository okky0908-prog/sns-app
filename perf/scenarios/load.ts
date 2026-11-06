// 負荷：同時に50人が10分間操作する。合否を判定する（docs/performance-test-plan.md「6.2 シナリオの種類」）。
//
// 1人（VU）の動きは lib/api.ts の userIteration（ログイン → 操作 → 3〜10秒待つ、60秒ごとに新しい投稿の件数）。
// 全員が同時にログインしないよう、始まりの1分で少しずつ人数を増やす。
// 監視役（1人）が10秒ごとにバックエンドの metrics（ヒープ・接続プール）を記録する。
//
// 人数・時間は環境変数で変えられる（例：VUS=10 DURATION=2m。perf/run.sh load の前に付ける）

import * as api from './lib/api.ts';

const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '10m';

export const options = {
  // k6 は既定で繰り返しのたびに Cookie を消す。リフレッシュトークンの Cookie を残すため、消さないようにする
  noCookiesReset: true,
  scenarios: {
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: VUS },
        { duration: DURATION, target: VUS },
      ],
      gracefulRampDown: '15s',
      exec: 'user',
    },
    monitor: api.monitorScenario(`${1 + api.minutes(DURATION)}m`),
  },
  thresholds: api.thresholds('load'),
};

export function user(): void {
  api.userIteration();
}

export function monitor(): void {
  api.monitorIteration();
}
