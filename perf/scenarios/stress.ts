// ストレス：人数を10人ずつ増やし、p95 が1秒を超える人数（限界）を探す（docs/performance-test-plan.md「6.2 シナリオの種類」）。
//
// 1人の動きは負荷と同じ（lib/api.ts の userIteration）。STEP 秒ごとに10人ずつ、MAX_VUS 人まで増やす。
// 合否は付けない。人数ごとの p95 は、結果の CSV を perf/analyze.py で集計して見る（k6 の要約は全体の値しか出ないため）。
// エラーが半分を超えたら（明らかに壊れたら）、そこで止める。
//
// 環境変数: MAX_VUS（既定 500）、STEP（既定 18s。500人なら約15分）

import * as api from './lib/api.ts';

const MAX_VUS = parseInt(__ENV.MAX_VUS || '500', 10);
const STEP = __ENV.STEP || '18s';

const stages: { duration: string; target: number }[] = [];
for (let target = 10; target <= MAX_VUS; target += 10) {
  stages.push({ duration: '1s', target });
  stages.push({ duration: STEP, target });
}
const totalSeconds = (MAX_VUS / 10) * (parseInt(STEP, 10) + 1);

export const options = {
  // k6 は既定で繰り返しのたびに Cookie を消す。リフレッシュトークンの Cookie を残すため、消さないようにする
  noCookiesReset: true,
  scenarios: {
    stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages,
      gracefulRampDown: '10s',
      exec: 'user',
    },
    monitor: api.monitorScenario(`${totalSeconds + 10}s`),
  },
  thresholds: api.thresholds('stress', ['p(95)>=0'], [{ threshold: 'rate<0.5', abortOnFail: true, delayAbortEval: '30s' }]),
};

export function user(): void {
  api.userIteration();
}

export function monitor(): void {
  api.monitorIteration();
}
