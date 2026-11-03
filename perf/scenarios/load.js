// 負荷：同時に50人が10分間操作する。合否を判定する（docs/performance-test-plan.md「6.2 シナリオの種類」）。
//
// 1人（VU）の動き:
//   - 最初にログインする（全員が同時にログインしないよう、始まりの1分で少しずつ人数を増やす）
//   - 「6.1 利用者の操作の割合」に従って操作を1つ選び、人が画面を読む時間（3〜10秒）だけ待つ、を繰り返す
//   - 60秒ごとに、新しい投稿の件数を確かめる
//
// 人数・時間は環境変数で変えられる（例：VUS=10 DURATION=2m。perf/run.sh load の前に付ける）

import { sleep } from 'k6';
import * as api from './lib/api.js';

const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '10m';
const NEW_COUNT_INTERVAL_MS = 60 * 1000;

export const options = {
  scenarios: {
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: VUS },
        { duration: DURATION, target: VUS },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: api.thresholds(),
};

let lastNewCountAt = 0;

export default function () {
  api.ensureLoggedIn();
  if (Date.now() - lastNewCountAt >= NEW_COUNT_INTERVAL_MS) {
    // 最初の1回は、新しい件数の基準になる「一番新しい投稿」を知るため、先にタイムラインを見る
    if (lastNewCountAt === 0) api.viewTimeline('following');
    api.checkNewCount();
    lastNewCountAt = Date.now();
  }
  api.doRandomAction();
  sleep(3 + Math.random() * 7);
}
