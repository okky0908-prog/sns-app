// k6 のシナリオで共通に使う、API の呼び出しと利用者の操作（docs/performance-test-plan.md「6. シナリオ」）。
//
// 環境変数（perf/run.sh が渡す）:
//   BASE_URL  接続先（既定は Docker の中から Mac のバックエンドを指す http://host.docker.internal:8080）
//   USERS     テストデータのユーザー数（perf/setup-db.sh の規模。基準は 500、10倍は 5000）
//
// それぞれの VU（仮想の利用者）は、自分の番号に対応するテスト用ユーザー（perf00001〜）でログインする。
// VU ごとに実行環境が別なので、下の session は VU ごとに別の値を持つ。

import http, { type RefinedResponse, type ResponseType } from 'k6/http';
import { check, sleep } from 'k6';
import { Gauge } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const USERS = parseInt(__ENV.USERS || '500', 10);
const PASSWORD = 'password123';

// 特別なデータ（perf/data/seed.sql）
export const HOT_LIKES_POST_ID = 1;
export const HOT_COMMENTS_POST_ID = 2;

// アクセストークンの有効期限は15分（application.yml の app.jwt.expiration）。切れる少し前に再発行する
const REFRESH_AFTER_MS = 14 * 60 * 1000;

// API ごとに応答時間を分けて見るための名前（k6 の name タグ）。ID などが入った URL ごとに分かれないよう、形でまとめる
export const API_NAMES: readonly string[] = [
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

// ---- API の応答の形（使う項目だけ。docs/openapi.yaml） ----

interface Post {
  id: number;
  likedByMe?: boolean;
  author?: { username: string };
}

interface Page {
  items: Post[];
  hasNext: boolean;
  nextCursor: string | null;
}

interface Measurement {
  statistic: string;
  value: number;
}

/** この VU のログインの状態と、見たことのある投稿 */
interface Session {
  n: number;
  username: string;
  token: string;
  issuedAt: number;
  latestPostId: number;
  posts: Post[];
}

type Method = 'POST' | 'DELETE';
type Response = RefinedResponse<ResponseType | undefined>;

let session: Session | null = null;

/** ログインしたあとの session（ログインの前に呼ばれたら、シナリオの書き間違い） */
function current(): Session {
  if (session === null) throw new Error('ログインの前に API を呼んでいます（先に ensureLoggedIn を呼ぶ）');
  return session;
}

export function username(n: number): string {
  return 'perf' + String(n).padStart(5, '0');
}

function randomInt(min: number, max: number): number {
  return min + Math.floor(Math.random() * (max - min + 1));
}

function pick<T>(list: readonly T[]): T {
  return list[Math.floor(Math.random() * list.length)];
}

function params(name: string, extraHeaders: Record<string, string> = {}) {
  const headers: Record<string, string> = { ...extraHeaders };
  if (session) {
    headers.Authorization = `Bearer ${session.token}`;
  }
  return { headers, tags: { name } };
}

function json<T>(res: Response): T | null {
  try {
    return res.json() as T;
  } catch {
    return null;
  }
}

function get<T = unknown>(name: string, path: string): T | null {
  const res = http.get(`${BASE_URL}${path}`, params(name));
  check(res, { [`${name} が 200`]: (r) => r.status === 200 });
  return json(res);
}

function send(
  method: string,
  name: string,
  path: string,
  body: string | null,
  expectedStatus: number,
  headers: Record<string, string> = {},
): void {
  const res = http.request(method, `${BASE_URL}${path}`, body, params(name, headers));
  check(res, { [`${name} が ${expectedStatus}`]: (r) => r.status === expectedStatus });
}

// ---- ログイン・再発行 ----

/** この VU のユーザーでログインする（1回目の呼び出しだけ）。トークンが古くなっていたら再発行する */
export function ensureLoggedIn(): void {
  if (session === null) {
    const n = ((__VU - 1) % USERS) + 1;
    login(n);
  } else if (Date.now() - session.issuedAt > REFRESH_AFTER_MS) {
    refresh();
  }
}

export function login(n: number): void {
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
  session = {
    n,
    username: username(n),
    token: res.json('accessToken') as string,
    issuedAt: Date.now(),
    latestPostId: 0,
    posts: [],
  };
}

/** アクセストークンを再発行する。リフレッシュトークンは、ログインのときに k6 の VU ごとの Cookie に入っている（シナリオの options に noCookiesReset: true が必要） */
export function refresh(): void {
  const s = current();
  const res = http.post(`${BASE_URL}/api/auth/refresh`, null, { tags: { name: 'POST /api/auth/refresh' } });
  check(res, { 'POST /api/auth/refresh が 200': (r) => r.status === 200 });
  if (res.status === 200) {
    s.token = res.json('accessToken') as string;
    s.issuedAt = Date.now();
  }
}

// ---- 利用者の操作（「6.1 利用者の操作の割合」の1行ずつ） ----

function remember(page: Page | null): void {
  if (page && Array.isArray(page.items) && page.items.length > 0) {
    const s = current();
    s.posts = page.items;
    s.latestPostId = Math.max(s.latestPostId, page.items[0].id);
  }
}

/** 見たことのある投稿から1つ選ぶ。いいね・コメントの多い投稿も、ときどき選ぶ */
function somePostId(): number {
  const r = Math.random();
  if (r < 0.05) return HOT_LIKES_POST_ID;
  if (r < 0.1) return HOT_COMMENTS_POST_ID;
  const { posts } = current();
  if (posts.length > 0) return pick(posts).id;
  return randomInt(1, 100);
}

/** 自分以外のユーザーを1人選ぶ。見たことのある投稿の作者か、ランダムなユーザー */
function someOtherUsername(): string {
  const s = current();
  const authors = s.posts
    .map((p) => p.author?.username)
    .filter((u): u is string => u !== undefined && u !== s.username);
  if (authors.length > 0 && Math.random() < 0.5) return pick(authors);
  let n = randomInt(1, USERS);
  if (n === s.n) n = (n % USERS) + 1;
  return username(n);
}

/** タイムラインを見る（フォロー中か全体）。続きを1〜2ページ読む */
export function viewTimeline(tab: 'following' | 'all' = Math.random() < 0.5 ? 'following' : 'all'): void {
  const path = tab === 'all' ? '/api/timeline/all' : '/api/timeline';
  const name = `GET ${path}`;
  let page = get<Page>(name, path);
  remember(page);
  const more = randomInt(1, 2);
  for (let i = 0; i < more && page && page.hasNext && page.nextCursor; i++) {
    page = get<Page>(name, `${path}?cursor=${encodeURIComponent(page.nextCursor)}`);
  }
}

/** 投稿の詳細とコメントを見る */
export function viewPost(postId: number = somePostId()): void {
  get('GET /api/posts/{postId}', `/api/posts/${postId}`);
  get('GET /api/posts/{postId}/comments', `/api/posts/${postId}/comments`);
}

/** プロフィールを見る（投稿一覧と、フォロー中かフォロワーの一覧） */
export function viewProfile(name: string = someOtherUsername()): void {
  get('GET /api/users/{username}', `/api/users/${name}`);
  get('GET /api/users/{username}/posts', `/api/users/${name}/posts`);
  if (Math.random() < 0.5) {
    get('GET /api/users/{username}/followers', `/api/users/${name}/followers`);
  } else {
    get('GET /api/users/{username}/following', `/api/users/${name}/following`);
  }
}

/** いいねを付ける・外す（method を省くと、見た投稿がいいね済みなら外し、それ以外は付ける） */
export function toggleLike(postId: number = somePostId(), method?: Method): void {
  if (method === undefined) {
    const post = current().posts.find((p) => p.id === postId);
    method = post?.likedByMe ? 'DELETE' : 'POST';
  }
  send(method, `${method} /api/posts/{postId}/likes`, `/api/posts/${postId}/likes`, null, 200);
}

/** フォローする・外す */
export function toggleFollow(
  name: string = someOtherUsername(),
  method: Method = Math.random() < 0.5 ? 'POST' : 'DELETE',
): void {
  send(method, `${method} /api/users/{username}/follow`, `/api/users/${name}/follow`, null, 200);
}

/** ユーザーを検索する */
export function searchUsers(q: string = pick(['perf', 'perf001', 'perf0042', '性能', 'テスト', '性能テスト 1'])): void {
  get('GET /api/users/search', `/api/users/search?q=${encodeURIComponent(q)}`);
}

/** 投稿する（画像なし）。API は multipart/form-data しか受け付けないので、本文を自分で組み立てる */
export function createPost(): void {
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
export function createComment(postId: number = somePostId()): void {
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
export function checkNewCount(): void {
  const since = current().latestPostId;
  get('GET /api/timeline/new-count', `/api/timeline/new-count?since=${since}`);
  get('GET /api/timeline/all/new-count', `/api/timeline/all/new-count?since=${since}`);
}

/** 「6.1 利用者の操作の割合」に従って、操作を1つ選んで行う */
export function doRandomAction(): void {
  const r = Math.random() * 100;
  if (r < 40) return viewTimeline();
  if (r < 60) return viewPost();
  if (r < 75) return viewProfile();
  if (r < 85) return Math.random() < 0.5 ? toggleLike() : toggleFollow();
  if (r < 90) return searchUsers();
  return Math.random() < 0.5 ? createPost() : createComment();
}

const NEW_COUNT_INTERVAL_MS = 60 * 1000;
let lastNewCountAt = 0;

/**
 * 利用者（VU）の1回分の動き。負荷・ストレス・耐久で共通に使う。
 *   - 最初にログインする（14分たったら再発行する）
 *   - 60秒ごとに、新しい投稿の件数を確かめる（最初の1回は、基準になる「一番新しい投稿」を知るため先にタイムラインを見る）
 *   - 操作を1つ選んで行い、人が画面を読む時間（3〜10秒）だけ待つ
 */
export function userIteration(): void {
  ensureLoggedIn();
  if (Date.now() - lastNewCountAt >= NEW_COUNT_INTERVAL_MS) {
    if (lastNewCountAt === 0) viewTimeline('following');
    checkNewCount();
    lastNewCountAt = Date.now();
  }
  doRandomAction();
  sleep(3 + Math.random() * 7);
}

// ---- 監視役（バックエンドの metrics を定期的に記録する） ----

// k6 の結果（CSV）に時刻つきで残し、perf/analyze.py で時間ごとの変化を見る
const heapUsedMb = new Gauge('backend_heap_used_mb');
const gcPauseTotalMs = new Gauge('backend_gc_pause_total_ms');
const processCpu = new Gauge('backend_process_cpu');
const hikariActive = new Gauge('backend_hikari_active');
const hikariPending = new Gauge('backend_hikari_pending');

function metricValue(path: string, statistic = 'VALUE'): number | null {
  const res = http.get(`${BASE_URL}/api/metrics/${path}`, params('GET /api/metrics/{name}'));
  if (res.status !== 200) return null;
  const measurements = (json<{ measurements?: Measurement[] }>(res)?.measurements) ?? [];
  const m = measurements.find((x) => x.statistic === statistic);
  return m ? m.value : null;
}

function record(gauge: Gauge, value: number | null, scale = 1): void {
  if (value !== null) gauge.add(value * scale);
}

/**
 * 監視役の1回分。専用のユーザー（一番最後の番号）でログインし、metrics を読んで記録する。
 * バックエンドを MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics で起動していないと 404 になり、何も記録しない。
 * 監視役のリクエストは、合格の基準（thresholds）に入れない（シナリオで分けている）
 */
export function monitorIteration(intervalSeconds = 10): void {
  if (session === null) login(USERS);
  else if (Date.now() - session.issuedAt > REFRESH_AFTER_MS) refresh();
  record(heapUsedMb, metricValue('jvm.memory.used?tag=area:heap'), 1 / 1024 / 1024);
  record(gcPauseTotalMs, metricValue('jvm.gc.pause', 'TOTAL_TIME'), 1000);
  record(processCpu, metricValue('process.cpu.usage'));
  record(hikariActive, metricValue('hikaricp.connections.active'));
  record(hikariPending, metricValue('hikaricp.connections.pending'));
  sleep(intervalSeconds);
}

/** "10m"・"90s"・"1h" を分にする（切り上げ。監視役を同じ長さだけ動かすため） */
export function minutes(text: string): number {
  const m = /^(\d+)([smh])$/.exec(text);
  if (!m) throw new Error(`時間は 10m・90s・1h の形で書く（${text}）`);
  const n = parseInt(m[1], 10);
  return m[2] === 'h' ? n * 60 : m[2] === 'm' ? n : Math.ceil(n / 60);
}

/** 監視役のシナリオ（options.scenarios に足す）。test の長さに合わせて duration を渡す */
export function monitorScenario(duration: string) {
  return { executor: 'constant-vus', vus: 1, duration, exec: 'monitor' } as const;
}

// ---- 合格の基準（docs/performance-test-plan.md「1. 目的と合格の基準」） ----

/** k6 の thresholds の1つ（式だけか、途中で止めるかなどの設定つき） */
type Threshold = string | { threshold: string; abortOnFail?: boolean; delayAbortEval?: string };

/**
 * 合否を決める基準（全部の API をまとめて p95 < 500ms、エラー率 1% 未満）に加え、
 * API ごとの p95 を結果に出すための式を付ける（API ごとの式は合否に使わない。必ず通る「0以上」にしている）。
 * scenario を渡すと、そのシナリオのリクエストだけで判定する（監視役のリクエストを入れないため）
 */
export function thresholds(
  scenario?: string,
  overall: Threshold[] = ['p(95)<500'],
  failed: Threshold[] = ['rate<0.01'],
): Record<string, Threshold[]> {
  const filter = scenario ? `{scenario:${scenario}}` : '';
  const t: Record<string, Threshold[]> = {
    [`http_req_duration${filter}`]: overall,
    [`http_req_failed${filter}`]: failed,
  };
  for (const name of API_NAMES) {
    t[`http_req_duration{name:${name}}`] = ['p(95)>=0'];
  }
  return t;
}
