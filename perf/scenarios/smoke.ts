// スモーク：1人で全部の操作を1回ずつ通す（docs/performance-test-plan.md「6.2 シナリオの種類」）。
// スクリプトとテストデータが正しく動くかの確認なので、ほかのシナリオの前に必ず流す。
// 速さは見ず、1つでも失敗したら（ステータスが期待と違ったら）k6 を失敗で終わらせる。

import * as api from './lib/api.ts';

export const options = {
  // k6 は既定で繰り返しのたびに Cookie を消す。リフレッシュトークンの Cookie を残すため、消さないようにする
  noCookiesReset: true,
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
};

export default function (): void {
  api.ensureLoggedIn();
  api.viewTimeline('following');
  api.viewTimeline('all');
  api.checkNewCount();
  api.viewPost(api.HOT_LIKES_POST_ID);
  api.viewPost(api.HOT_COMMENTS_POST_ID);
  api.viewProfile();
  // いいね・フォローは、付ける・外すの両方を通す（スモークを何回流しても同じ結果になるよう、最後は外した状態に戻す）
  api.toggleLike(api.HOT_COMMENTS_POST_ID, 'POST');
  api.toggleLike(api.HOT_COMMENTS_POST_ID, 'DELETE');
  const other = api.username(api.USERS);
  api.toggleFollow(other, 'POST');
  api.toggleFollow(other, 'DELETE');
  api.searchUsers();
  api.createPost();
  api.createComment(api.HOT_LIKES_POST_ID);
  api.refresh();
  api.checkNewCount();
}
