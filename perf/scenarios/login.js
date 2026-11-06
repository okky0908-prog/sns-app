// ログイン集中：ログインだけを集中させ、BCrypt が CPU をどれだけ使うか、ほかの API が巻き込まれて遅くならないかを見る
// （docs/performance-test-plan.md「5. 重点的に確かめる箇所」「6.2 シナリオの種類」）。
//
// - logins：1秒あたりのログインの回数を、1回から RATE 回まで1分かけて増やし、そのまま4分続ける（合わせて5分）
// - users：ふだんどおり操作している人（BACKGROUND_VUS 人）。この人たちの p95 が、ログイン集中で悪くなるかを見る
// - monitor：バックエンドの CPU（process.cpu.usage）とヒープを記録する
//
// 合否は付けない（何回まで耐えられるかを記録する）。時間ごとの変化は perf/analyze.py で見る。
//
// 環境変数: RATE（1秒あたりのログイン。既定 20）、BACKGROUND_VUS（既定 20）

import { check } from 'k6';
import * as api from './lib/api.js';

const RATE = parseInt(__ENV.RATE || '20', 10);
const BACKGROUND_VUS = parseInt(__ENV.BACKGROUND_VUS || '20', 10);

export const options = {
  // k6 は既定で繰り返しのたびに Cookie を消す。リフレッシュトークンの Cookie を残すため、消さないようにする
  noCookiesReset: true,
  scenarios: {
    logins: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 300,
      stages: [
        { duration: '1m', target: RATE },
        { duration: '4m', target: RATE },
      ],
      exec: 'loginOnly',
    },
    users: {
      executor: 'constant-vus',
      vus: BACKGROUND_VUS,
      duration: '5m',
      exec: 'user',
    },
    monitor: api.monitorScenario('5m'),
  },
  thresholds: Object.assign(api.thresholds('users', ['p(95)>=0'], ['rate>=0']), {
    'http_req_duration{scenario:logins}': ['p(95)>=0'],
    'http_req_failed{scenario:logins}': ['rate>=0'],
    // 到着の速さに VU が追いつかず、ログインを始められなかった回数（多ければ、ログインが詰まっている）
    dropped_iterations: ['count>=0'],
  }),
};

export function loginOnly() {
  // ふだんの利用者（users）と重ならないよう、後ろの半分の番号のユーザーでログインする
  const half = Math.floor(api.USERS / 2);
  const n = half + 1 + Math.floor(Math.random() * (api.USERS - half - 1));
  try {
    api.login(n);
  } catch (e) {
    check(null, { 'ログインの集中でログインできた': () => false });
  }
}

export function user() {
  api.userIteration();
}

export function monitor() {
  api.monitorIteration(5);
}
