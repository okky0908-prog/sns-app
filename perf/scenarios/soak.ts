// 耐久：負荷と同じ50人で60分動かし、劣化しないか（応答時間・ヒープが増え続けないか）を見る
// （docs/performance-test-plan.md「6.2 シナリオの種類」）。
//
// アクセストークンの期限（15分）をまたぐので、14分ごとに再発行する（POST /api/auth/refresh。lib/api.ts の ensureLoggedIn）。
// 合格の基準は負荷と同じ（全体の p95 < 500ms、エラー率 1% 未満）。
// 時間ごとの p95 とヒープの変化は、結果の CSV を perf/analyze.py で集計して見る。
//
// 環境変数: VUS（既定 50）、DURATION（既定 60m）

import * as api from './lib/api.ts';

const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '60m';

export const options = {
  // k6 は既定で繰り返しのたびに Cookie を消す。リフレッシュトークンの Cookie を残すため、消さないようにする
  noCookiesReset: true,
  scenarios: {
    soak: {
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
  thresholds: Object.assign(api.thresholds('soak'), {
    // 期限をまたいだ再発行が、どの VU でも成功していること（check タグはチェックの名前）
    'checks{check:POST /api/auth/refresh が 200}': ['rate==1'],
  }),
};

export function user(): void {
  api.userIteration();
}

export function monitor(): void {
  api.monitorIteration(30);
}
