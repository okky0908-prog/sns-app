// k6 のシナリオで共通に使う、API の呼び出しと利用者の操作（docs/performance-test-plan.md「6. シナリオ」）。
//
// 環境変数（perf/run.sh が渡す）:
//   BASE_URL  接続先（既定は Docker の中から Mac のバックエンドを指す http://host.docker.internal:8080）
//   USERS     テストデータのユーザー数（perf/setup-db.sh の規模。基準は 500、10倍は 5000）
//
// それぞれの VU（仮想の利用者）は、自分の番号に対応するテスト用ユーザー（perf00001〜）でログインする。
// VU ごとに JavaScript の実行環境が別なので、下の session は VU ごとに別の値を持つ。

import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const USERS = parseInt(__ENV.USERS || '500', 10);
const PASSWORD = 'password123';

// 特別なデータ（perf/data/seed.sql）
export const HOT_LIKES_POST_ID = 1;
export const HOT_COMMENTS_POST_ID = 2;

// アクセストークンの有効期限は15分（application.yml の app.jwt.expiration）。切れる少し前に再発行する
const REFRESH_AFTER_MS = 14 * 60 * 1000;

// API ごとに応答時間を分けて見るための名前（k6 の name タグ）。ID などが入った URL ごとに分かれないよう、形でまとめる
export const API_NAMES = [
  'POST /api/auth/login',
  'POST /api/auth/refresh',
  'GET /api/timeline',
  'GET /api/timeline/all',
  'GET /api/timeline/new-count',
  'GET /api/timeline/all/new-count',
  'GET /api/posts/{postId}',
  'GET /api/posts/{postId}/comments',
  'POST /api/posts/{postId}/comments',
  'POST /api/posts/{postId}/likes',
  'DELETE /api/posts/{postId}/likes',
  'POST /api/posts',
  'GET /api/users/{username}',
  'GET /api/users/{username}/posts',
  'GET /api/users/{username}/followers',
  'GET /api/users/{username}/following',
  'POST /api/users/{username}/follow',
  'DELETE /api/users/{username}/follow',
  'GET /api/users/search',
];

let session = null;

export function username(n) {
  return 'perf' + String(n).padStart(5, '0');
}

function randomInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function params(name, extra = {}) {
  const headers = Object.assign({}, extra.headers);
  if (session) {
    headers.Authorization = `Bearer ${session.token}`;
  }
  return { headers, tags: { name } };
}

function json(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function get(name, path) {
  const res = http.get(`${BASE_URL}${path}`, params(name));
  check(res, { [`${name} が 200`]: (r) => r.status === 200 });
  return json(res);
}

function send(method, name, path, body, expectedStatus, headers = {}) {
  const res = http.request(method, `${BASE_URL}${path}`, body, params(name, { headers }));
  check(res, { [`${name} が ${expectedStatus}`]: (r) => r.status === expectedStatus });
  return json(res);
}

// ---- ログイン・再発行 ----

/** この VU のユーザーでログインする（1回目の呼び出しだけ）。トークンが古くなっていたら再発行する */
export function ensureLoggedIn() {
  if (session === null) {
    const n = ((__VU - 1) % USERS) + 1;
    login(n);
  } else if (Date.now() - session.issuedAt > REFRESH_AFTER_MS) {
    refresh();
  }
}

export function login(n) {
  session = null;
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: `${username(n)}@example.com`, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/auth/login' } },
  );
  const ok = check(res, { 'POST /api/auth/login が 200': (r) => r.status === 200 });
  if (!ok) {
    throw new Error(`ログインできません（${username(n)}、ステータス ${res.status}）`);
  }
  session = { n, username: username(n), token: res.json('accessToken'), issuedAt: Date.now(), latestPostId: 0, posts: [] };
}

/** アクセストークンを再発行する。リフレッシュトークンは、ログインのときに k6 の VU ごとの Cookie に入っている */
export function refresh() {
  const res = http.post(`${BASE_URL}/api/auth/refresh`, null, { tags: { name: 'POST /api/auth/refresh' } });
  check(res, { 'POST /api/auth/refresh が 200': (r) => r.status === 200 });
  if (res.status === 200) {
    session.token = res.json('accessToken');
    session.issuedAt = Date.now();
  }
}

// ---- 利用者の操作（「6.1 利用者の操作の割合」の1行ずつ） ----

function remember(page) {
  if (page && Array.isArray(page.items) && page.items.length > 0) {
    session.posts = page.items;
    session.latestPostId = Math.max(session.latestPostId, page.items[0].id);
  }
}

/** 見たことのある投稿から1つ選ぶ。いいね・コメントの多い投稿も、ときどき選ぶ */
function somePostId() {
  const r = Math.random();
  if (r < 0.05) return HOT_LIKES_POST_ID;
  if (r < 0.1) return HOT_COMMENTS_POST_ID;
  if (session.posts.length > 0) return pick(session.posts).id;
  return randomInt(1, 100);
}

/** 自分以外のユーザーを1人選ぶ。見たことのある投稿の作者か、ランダムなユーザー */
function someOtherUsername() {
  const authors = session.posts.map((p) => p.author && p.author.username).filter((u) => u && u !== session.username);
  if (authors.length > 0 && Math.random() < 0.5) return pick(authors);
  let n = randomInt(1, USERS);
  if (n === session.n) n = (n % USERS) + 1;
  return username(n);
}

/** タイムラインを見る（フォロー中か全体）。続きを1〜2ページ読む */
export function viewTimeline(tab = Math.random() < 0.5 ? 'following' : 'all') {
  const path = tab === 'all' ? '/api/timeline/all' : '/api/timeline';
  const name = `GET ${path}`;
  let page = get(name, path);
  remember(page);
  const more = randomInt(1, 2);
  for (let i = 0; i < more && page && page.hasNext; i++) {
    page = get(name, `${path}?cursor=${encodeURIComponent(page.nextCursor)}`);
  }
}

/** 投稿の詳細とコメントを見る */
export function viewPost(postId = somePostId()) {
  get('GET /api/posts/{postId}', `/api/posts/${postId}`);
  get('GET /api/posts/{postId}/comments', `/api/posts/${postId}/comments`);
}

/** プロフィールを見る（投稿一覧と、フォロー中かフォロワーの一覧） */
export function viewProfile(name = someOtherUsername()) {
  get('GET /api/users/{username}', `/api/users/${name}`);
  get('GET /api/users/{username}/posts', `/api/users/${name}/posts`);
  if (Math.random() < 0.5) {
    get('GET /api/users/{username}/followers', `/api/users/${name}/followers`);
  } else {
    get('GET /api/users/{username}/following', `/api/users/${name}/following`);
  }
}

/** いいねを付ける・外す（method を省くと、見た投稿がいいね済みなら外し、それ以外は付ける） */
export function toggleLike(postId = somePostId(), method = undefined) {
  if (method === undefined) {
    const post = session.posts.find((p) => p.id === postId);
    method = post && post.likedByMe ? 'DELETE' : 'POST';
  }
  send(method, `${method} /api/posts/{postId}/likes`, `/api/posts/${postId}/likes`, null, 200);
}

/** フォローする・外す */
export function toggleFollow(name = someOtherUsername(), method = Math.random() < 0.5 ? 'POST' : 'DELETE') {
  send(method, `${method} /api/users/{username}/follow`, `/api/users/${name}/follow`, null, 200);
}

/** ユーザーを検索する */
export function searchUsers(q = pick(['perf', 'perf001', 'perf0042', '性能', 'テスト', '性能テスト 1'])) {
  get('GET /api/users/search', `/api/users/search?q=${encodeURIComponent(q)}`);
}

/** 投稿する（画像なし）。API は multipart/form-data しか受け付けないので、本文を自分で組み立てる */
export function createPost() {
  const boundary = `----k6${Date.now()}${__VU}`;
  const body =
    `--${boundary}\r\n` +
    'Content-Disposition: form-data; name="content"\r\n\r\n' +
    `性能テストの新しい投稿（VU ${__VU}・${__ITER}）\r\n` +
    `--${boundary}--\r\n`;
  send('POST', 'POST /api/posts', '/api/posts', body, 201, {
    'Content-Type': `multipart/form-data; boundary=${boundary}`,
  });
}

/** コメントする */
export function createComment(postId = somePostId()) {
  send(
    'POST',
    'POST /api/posts/{postId}/comments',
    `/api/posts/${postId}/comments`,
    JSON.stringify({ content: `性能テストのコメント（VU ${__VU}・${__ITER}）` }),
    201,
    { 'Content-Type': 'application/json' },
  );
}

/** 新しい投稿の件数を確かめる（画面を開いている全員が60秒ごとに呼ぶ） */
export function checkNewCount() {
  const since = session.latestPostId;
  get('GET /api/timeline/new-count', `/api/timeline/new-count?since=${since}`);
  get('GET /api/timeline/all/new-count', `/api/timeline/all/new-count?since=${since}`);
}

/** 「6.1 利用者の操作の割合」に従って、操作を1つ選んで行う */
export function doRandomAction() {
  const r = Math.random() * 100;
  if (r < 40) return viewTimeline();
  if (r < 60) return viewPost();
  if (r < 75) return viewProfile();
  if (r < 85) return Math.random() < 0.5 ? toggleLike() : toggleFollow();
  if (r < 90) return searchUsers();
  return Math.random() < 0.5 ? createPost() : createComment();
}

// ---- 合格の基準（docs/performance-test-plan.md「1. 目的と合格の基準」） ----

/**
 * 合否を決める基準（全部の API をまとめて p95 < 500ms、エラー率 1% 未満）に加え、
 * API ごとの p95 を結果に出すための式を付ける（API ごとの式は合否に使わない。必ず通る「0以上」にしている）
 */
export function thresholds() {
  const t = {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  };
  for (const name of API_NAMES) {
    t[`http_req_duration{name:${name}}`] = ['p(95)>=0'];
  }
  return t;
}
