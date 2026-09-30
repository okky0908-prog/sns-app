'use strict';

/*
 * X風SNSアプリ 静的モック
 * - データはこのファイル内のメモリ上だけに持つ（リロードで初期データに戻る）
 * - 画面の切り替えは URL のハッシュ（#/...）で行う。ページを読み込み直さないので、
 *   画面を移動したりログアウトして別ユーザーでログインしたりしてもデータは消えない
 * - 仕様は docs/features.md・docs/screens.md・docs/feature-specs/ に合わせている
 */

// ===== 定数（docs/features.md の業務ルール） =====
const PAGE_SIZE = 20;
const POST_MAX = 280;
const COMMENT_MAX = 280;
const BIO_MAX = 160;
const DISPLAY_NAME_MAX = 50;
const SEARCH_MAX = 50;
const SEARCH_DELAY_MS = 300;
const IMAGE_MAX_COUNT = 4;
const IMAGE_MAX_BYTES = 5 * 1024 * 1024;
const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif'];
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 72;
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const AVATAR_COLORS = ['#1d9bf0', '#f91880', '#7856ff', '#ff7a00', '#00ba7c', '#e0245e', '#6b7c8c'];

// ===== データ（docs/database.md のテーブルと同じ形） =====
const db = {
  users: [], // { id, username, displayName, email, password, bio, iconUrl, createdAt }
  posts: [], // { id, userId, content, images: [{ url, sortOrder }], createdAt, editedAt }
  comments: [], // { id, postId, userId, content, createdAt }
  likes: [], // { postId, userId, createdAt }
  follows: [], // { followerId, followeeId, createdAt }
};
const nextId = { user: 1, post: 1, comment: 1 };
let currentUserId = null;

// ===== 初期データ =====
function makeImage(label, color1, color2) {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600">` +
    `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">` +
    `<stop offset="0" stop-color="${color1}"/><stop offset="1" stop-color="${color2}"/>` +
    `</linearGradient></defs>` +
    `<rect width="800" height="600" fill="url(#g)"/>` +
    `<text x="400" y="320" font-size="60" text-anchor="middle" fill="#ffffff" font-family="sans-serif">${label}</text>` +
    `</svg>`;
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
}

function seed() {
  const now = Date.now();
  const users = [
    ['yamada', '山田太郎', 'Javaを勉強中の受講生です。\nSpring Bootでアプリを作っています。', 60],
    ['sato', '佐藤花子', 'Reactが好きです。フロントエンドを中心に勉強中。', 50],
    ['suzuki', '鈴木一郎', 'よろしくお願いします', 40],
    ['tanaka', '田中美咲', 'インフラ（AWS）を勉強中。Terraformが楽しい。', 20],
    ['yama_dev', 'やまちゃん', 'React勉強中', 5],
    ['hanako_k', '小山花子', '', 1],
  ];
  for (const [username, displayName, bio, daysAgo] of users) {
    db.users.push({
      id: nextId.user++,
      username,
      displayName,
      email: `${username.replace('_', '')}@example.com`,
      password: 'password123',
      bio,
      iconUrl: null,
      createdAt: now - daysAgo * DAY,
    });
  }

  const u = (username) => findUserByUsername(username).id;
  const follow = (a, b, daysAgo) =>
    db.follows.push({ followerId: u(a), followeeId: u(b), createdAt: now - daysAgo * DAY });
  follow('yamada', 'sato', 30);
  follow('yamada', 'suzuki', 20);
  follow('yamada', 'yama_dev', 3);
  follow('sato', 'yamada', 40);
  follow('sato', 'tanaka', 10);
  follow('sato', 'suzuki', 8);
  follow('suzuki', 'yamada', 35);
  follow('tanaka', 'sato', 15);
  follow('tanaka', 'yamada', 12);
  follow('yama_dev', 'yamada', 4);

  // [ユーザー, 本文, 何分前, 画像のラベル, 編集済みか]
  const posts = [
    ['sato', '今日はReactのuseEffectを勉強しました。\n依存配列、奥が深い…', 3],
    ['yamada', '今日はSpring Bootの勉強をしました。\nJPAのリレーションがやっと理解できてきた！', 12, ['Spring Boot', 'ノート']],
    ['tanaka', 'TerraformでALBを作ってみた。ヘルスチェックのパス設定でハマった😇', 25, ['AWS構成図']],
    ['yama_dev', 'はじめまして！React勉強中です。よろしくお願いします🙌', 40],
    ['suzuki', 'カフェで作業中☕', 70, ['カフェ']],
    ['yamada', 'N+1問題、SQLのログを見て初めて実感した。', 95],
    ['sato', 'CSS Modules便利。クラス名がぶつからないの最高。', 130],
    ['hanako_k', '今日からこのSNSを使い始めました！', 150],
    ['yamada', 'Flywayのマイグレーション、一度適用したファイルは書き換えちゃダメなのか…', 200],
    ['suzuki', '朝活でアルゴリズムの問題を3問解いた', 260],
    ['tanaka', 'RDSのスナップショットを取り忘れてdestroyした。学びとしておく。', 330],
    ['sato', '週末は図書館で勉強。', 420, ['図書館', '本', 'ノート', 'コーヒー']],
    ['yamada', '280文字って意外とたくさん書ける。（追記：誤字を直しました）', 500, null, true],
    ['yama_dev', 'TypeScriptの型エラーと仲良くなりたい', 600],
    ['suzuki', 'Gitのブランチ運用、IssueからPRまでの流れに慣れてきた', 720, ['Issue', 'Branch', 'PR']],
    ['sato', 'useStateとuseReducer、どっちを使うか迷う', 900],
    ['yamada', 'ER図を書いてからコードを書くと迷いが減る気がする', 1100],
    ['suzuki', '今日は休憩日にする', 1300],
    ['yamada', 'BCryptって72バイトまでしか扱えないの知らなかった', 1500],
    ['sato', 'モックを作ってから実装すると画面のイメージが固まる', 1 * DAY / MINUTE + 200],
    ['yama_dev', 'Viteの起動が速くて感動した', 1 * DAY / MINUTE + 500],
    ['yamada', 'REST APIのURL設計、名詞で考えるとすっきりする', 2 * DAY / MINUTE],
    ['suzuki', 'Docker Composeで PostgreSQL を立ち上げた', 2 * DAY / MINUTE + 300, ['Docker']],
    ['sato', 'ハートのアニメーションを作ってみたい', 3 * DAY / MINUTE],
    ['yamada', 'Spring Securityの設定、最初は難しいけど少しずつ', 3 * DAY / MINUTE + 400],
    ['suzuki', 'レビューでもらった指摘をメモしておく', 4 * DAY / MINUTE],
    ['tanaka', 'CloudFrontのOACの設定をした', 5 * DAY / MINUTE],
    ['sato', 'フォームの入力チェック、フロントとサーバーの両方でやる理由がわかった', 6 * DAY / MINUTE],
    ['yamada', 'このアプリの要件定義を書き始めた！', 8 * DAY / MINUTE],
    ['suzuki', 'はじめての投稿です', 12 * DAY / MINUTE],
    ['sato', 'はじめまして、佐藤です', 20 * DAY / MINUTE],
    ['yamada', 'はじめまして！Java勉強中の山田です', 30 * DAY / MINUTE],
  ];
  const imageColors = [
    ['#1d9bf0', '#7856ff'], ['#ff7a00', '#f91880'], ['#00ba7c', '#1d9bf0'], ['#7856ff', '#f91880'],
  ];
  // 古い順に ID を振る（新しい投稿ほど ID が大きくなるようにする）
  [...posts].reverse().forEach(([username, content, minutesAgo, labels, edited]) => {
    const createdAt = now - minutesAgo * MINUTE;
    db.posts.push({
      id: nextId.post++,
      userId: u(username),
      content,
      images: (labels || []).map((label, i) => ({
        url: makeImage(label, ...imageColors[i % imageColors.length]),
        sortOrder: i + 1,
      })),
      createdAt,
      editedAt: edited ? createdAt + 10 * MINUTE : null,
    });
  });

  // コメント：山田さんの Spring Boot の投稿には21件以上付けて「さらに表示」を確認できるようにする
  const springPost = db.posts.find((p) => p.content.startsWith('今日はSpring Boot'));
  const commenters = ['sato', 'suzuki', 'tanaka', 'yama_dev', 'hanako_k'];
  const texts = [
    'わかりやすいです！', '自分も勉強中です', 'JPAむずかしいですよね', 'おつかれさまです',
    '@OneToMany のところ、自分もハマりました', 'ノートきれい！', '参考になります',
  ];
  for (let i = 0; i < 23; i++) {
    const isOwn = i === 5 || i === 21;
    db.comments.push({
      id: nextId.comment++,
      postId: springPost.id,
      userId: u(isOwn ? 'yamada' : commenters[i % commenters.length]),
      content: isOwn ? 'ありがとうございます！がんばります' : texts[i % texts.length],
      createdAt: springPost.createdAt + (i + 1) * 20 * 1000,
    });
  }
  const addComment = (contentStart, username, content, minutesAfter) => {
    const post = db.posts.find((p) => p.content.startsWith(contentStart));
    db.comments.push({
      id: nextId.comment++,
      postId: post.id,
      userId: u(username),
      content,
      createdAt: post.createdAt + minutesAfter * MINUTE,
    });
  };
  addComment('今日はReact', 'yamada', 'useEffect、自分も苦戦中です', 1);
  addComment('TerraformでALB', 'yamada', 'ヘルスチェック、何のパスにしましたか？', 5);
  addComment('TerraformでALB', 'tanaka', '/api/health にしました！', 8);
  addComment('はじめまして！React', 'yamada', 'よろしくお願いします！', 10);
  addComment('はじめまして！React', 'sato', 'React仲間ですね🙌', 15);
  addComment('週末は図書館', 'suzuki', 'いいですね', 30);

  // いいね：決まった規則でばらつかせる（一部の投稿は0件にする）
  for (const post of db.posts) {
    if (post.id % 6 === 0) continue;
    for (const user of db.users) {
      if ((post.id * 31 + user.id * 17) % 5 < 2) {
        db.likes.push({ postId: post.id, userId: user.id, createdAt: post.createdAt + MINUTE });
      }
    }
  }
}

// ===== データの読み出し・集計 =====
const me = () => db.users.find((user) => user.id === currentUserId) || null;
const findUserById = (id) => db.users.find((user) => user.id === id) || null;
const findPostById = (id) => db.posts.find((post) => post.id === id) || null;

function findUserByUsername(username) {
  const lower = String(username).toLowerCase();
  return db.users.find((user) => user.username.toLowerCase() === lower) || null;
}

const likeCount = (postId) => db.likes.filter((like) => like.postId === postId).length;
const commentCount = (postId) => db.comments.filter((c) => c.postId === postId).length;
const likedByMe = (postId) => db.likes.some((like) => like.postId === postId && like.userId === currentUserId);
const isFollowing = (followerId, followeeId) =>
  db.follows.some((f) => f.followerId === followerId && f.followeeId === followeeId);
const followingCount = (userId) => db.follows.filter((f) => f.followerId === userId).length;
const followerCount = (userId) => db.follows.filter((f) => f.followeeId === userId).length;
const byNewest = (a, b) => b.createdAt - a.createdAt || b.id - a.id;

// ===== 小さな道具 =====

// 文字数は「見た目の文字数」で数える（絵文字も1文字）
const countChars = (text) => [...text].length;

function formatRelativeTime(time) {
  const diff = Date.now() - time;
  if (diff < HOUR) return `${Math.max(1, Math.floor(diff / MINUTE))}分前`;
  if (diff < DAY) return `${Math.floor(diff / HOUR)}時間前`;
  const d = new Date(time);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`;
}

// DOM を組み立てる。文字列は必ずテキストとして入れる（XSS 対策。innerHTML は使わない）
function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
    else if (key === 'value') el.value = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : String(child));
  }
  return el;
}

const userPath = (user) => `#/users/${encodeURIComponent(user.username)}`;

let toastTimer = null;
function toast(message, isError = false) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.toggle('error', isError);
  el.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 2500);
}

let confirmResolver = null;
function confirmDialog(message, okLabel = 'OK') {
  document.getElementById('confirm-message').textContent = message;
  document.getElementById('confirm-ok').textContent = okLabel;
  document.getElementById('confirm-dialog').classList.remove('hidden');
  document.getElementById('confirm-ok').focus();
  return new Promise((resolve) => {
    confirmResolver = resolve;
  });
}

function closeConfirm(result) {
  document.getElementById('confirm-dialog').classList.add('hidden');
  if (confirmResolver) confirmResolver(result);
  confirmResolver = null;
}

function openLightbox(url) {
  document.getElementById('lightbox-image').src = url;
  document.getElementById('lightbox').classList.remove('hidden');
}

function readAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

// 画像のチェック（形式・サイズ）。問題があればエラーメッセージを返す
function validateImageFile(file) {
  if (!IMAGE_TYPES.includes(file.type)) return 'jpg・png・gif の画像を選択してください';
  if (file.size > IMAGE_MAX_BYTES) return '5MB以下の画像を選択してください';
  return null;
}

// ===== 共通部品 =====
function renderAvatar(user, size = '') {
  const className = 'avatar' + (size ? ` ${size}` : '');
  if (user.iconUrl) return h('img', { class: className, src: user.iconUrl, alt: '' });
  return h(
    'span',
    { class: className, style: `background:${AVATAR_COLORS[user.id % AVATAR_COLORS.length]}`, 'aria-hidden': 'true' },
    [...user.displayName][0] || '?',
  );
}

// ユーザーへのリンク（docs/screens.md「ユーザーへのリンク（共通）」）
// 投稿カードの中でクリックしても、投稿詳細へは移動しないようにする
function renderUserLink(user, ...children) {
  return h('a', { class: 'user-link', href: userPath(user), onclick: (e) => e.stopPropagation() }, ...children);
}

const renderAvatarLink = (user, size) => renderUserLink(user, renderAvatar(user, size));

const renderNameLink = (user) =>
  renderUserLink(user, h('span', { class: 'display-name' }, user.displayName), ' ', h('span', { class: 'username' }, `@${user.username}`));

function renderFollowButton(user) {
  if (!me() || user.id === currentUserId) return null;
  const following = isFollowing(currentUserId, user.id);
  const button = following
    ? h(
        'button',
        { type: 'button', class: 'button follow-button is-following' },
        h('span', { class: 'label-default' }, 'フォロー中'),
        h('span', { class: 'label-hover' }, 'フォロー解除'),
      )
    : h('button', { type: 'button', class: 'button dark follow-button' }, 'フォローする');
  button.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleFollow(user.id);
  });
  return button;
}

function toggleFollow(userId) {
  if (!findUserById(userId)) {
    toast('このユーザーは見つかりません', true);
    return;
  }
  if (userId === currentUserId) return; // 自分自身はフォローできない
  const index = db.follows.findIndex((f) => f.followerId === currentUserId && f.followeeId === userId);
  if (index >= 0) db.follows.splice(index, 1);
  else db.follows.push({ followerId: currentUserId, followeeId: userId, createdAt: Date.now() });
  refresh();
}

// S-09 フォロー一覧・S-10 ユーザー検索で共通の行
function renderUserRow(user) {
  const firstLine = (user.bio || '').split('\n')[0];
  return h(
    'div',
    { class: 'user-row', onclick: () => (location.hash = userPath(user)) },
    renderAvatarLink(user),
    h('div', { class: 'user-row-main' }, renderNameLink(user), firstLine ? h('p', { class: 'user-row-bio' }, firstLine) : null),
    renderFollowButton(user),
  );
}

// 続きがなければ空のフラグメントを返す（append に null を渡すと「null」と表示されてしまうため）
function renderMoreButton(total, shown, label, onMore) {
  if (shown >= total) return document.createDocumentFragment();
  return h('div', { class: 'more-area' }, h('button', { type: 'button', class: 'button', onclick: onMore }, label));
}

// ===== 投稿カード =====
function renderPostCard(post, { detail = false } = {}) {
  const author = findUserById(post.userId);
  const card = h('article', { class: 'post-card' + (detail ? ' detail' : ''), dataset: { postId: post.id } });
  if (!detail) {
    card.addEventListener('click', () => (location.hash = `#/posts/${post.id}`));
  }

  const head = h(
    'div',
    { class: 'post-head' },
    renderNameLink(author),
    h('span', { class: 'meta' }, `· ${formatRelativeTime(post.createdAt)}`),
    post.editedAt ? h('span', { class: 'meta' }, '· 編集済み') : null,
    post.userId === currentUserId ? renderPostMenu(post) : null,
  );

  const images = post.images.length
    ? h(
        'div',
        { class: `post-images count-${post.images.length}` },
        post.images.map((image) =>
          h('img', {
            src: image.url,
            alt: '投稿画像',
            onclick: (e) => {
              e.stopPropagation();
              openLightbox(image.url);
            },
          }),
        ),
      )
    : null;

  // インプレッション数・リツイートのボタンは置かない（差別化）
  const actions = h('div', { class: 'post-actions' }, renderCommentButton(post), renderLikeButton(post));

  card.append(
    renderAvatarLink(author),
    h('div', { class: 'post-body' }, head, post.content ? h('p', { class: 'post-content' }, post.content) : null, images, actions),
  );
  return card;
}

function renderCommentButton(post) {
  const count = commentCount(post.id);
  return h(
    'button',
    {
      type: 'button',
      class: 'action-button comment',
      'aria-label': 'コメント',
      dataset: { commentPostId: post.id },
      onclick: (e) => {
        e.stopPropagation();
        const input = document.getElementById('comment-input');
        if (currentRoute.name === 'post' && currentRoute.postId === post.id && input) input.focus();
        else location.hash = `#/posts/${post.id}?focus=comment`;
      },
    },
    h('span', { class: 'action-icon' }, '💬'),
    h('span', { class: 'action-count' }, count > 0 ? count : ''),
  );
}

function renderLikeButton(post) {
  const button = h('button', { type: 'button', class: 'action-button like', dataset: { likePostId: post.id } });
  fillLikeButton(button, post.id);
  button.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleLike(post.id);
  });
  return button;
}

// いいね数が0のときは数字を出さない
function fillLikeButton(button, postId) {
  const liked = likedByMe(postId);
  const count = likeCount(postId);
  button.classList.toggle('liked', liked);
  button.setAttribute('aria-label', liked ? 'いいねを取り消す' : 'いいね');
  button.setAttribute('aria-pressed', String(liked));
  button.replaceChildren(h('span', { class: 'action-icon' }, liked ? '♥' : '♡'), h('span', { class: 'action-count' }, count > 0 ? count : ''));
}

// 押したらすぐに表示を切り替える（画面の他の部分は描き直さない）
function toggleLike(postId) {
  if (!findPostById(postId)) {
    toast('この投稿は削除されています', true);
    return;
  }
  const index = db.likes.findIndex((like) => like.postId === postId && like.userId === currentUserId);
  if (index >= 0) db.likes.splice(index, 1);
  else db.likes.push({ postId, userId: currentUserId, createdAt: Date.now() });
  document.querySelectorAll(`[data-like-post-id="${postId}"]`).forEach((button) => fillLikeButton(button, postId));
}

function renderPostMenu(post) {
  const list = h(
    'div',
    { class: 'menu-list hidden' },
    h('button', { type: 'button', onclick: () => { closeMenus(); openPostModal('edit', post.id); } }, '編集'),
    h('button', { type: 'button', class: 'danger', onclick: () => { closeMenus(); deletePost(post.id); } }, '削除'),
  );
  const toggle = h(
    'button',
    {
      type: 'button',
      class: 'icon-button',
      'aria-label': 'メニュー',
      onclick: () => {
        const wasHidden = list.classList.contains('hidden');
        closeMenus();
        if (wasHidden) list.classList.remove('hidden');
      },
    },
    '…',
  );
  return h('div', { class: 'menu-wrapper', onclick: (e) => e.stopPropagation() }, toggle, list);
}

function closeMenus() {
  document.querySelectorAll('.menu-list').forEach((menu) => menu.classList.add('hidden'));
}

async function deletePost(postId) {
  const ok = await confirmDialog('この投稿を削除しますか？\nこの操作は取り消せません', '削除する');
  if (!ok) return;
  if (!findPostById(postId)) {
    toast('この投稿はすでに削除されています', true);
    refresh();
    return;
  }
  // 画像・コメント・いいねも一緒に消す（DB の ON DELETE CASCADE に相当）
  db.posts = db.posts.filter((p) => p.id !== postId);
  db.comments = db.comments.filter((c) => c.postId !== postId);
  db.likes = db.likes.filter((like) => like.postId !== postId);
  toast('投稿を削除しました');
  if (currentRoute.name === 'post') location.hash = '#/';
  else refresh();
}

function renderPostList(container, posts, emptyContent) {
  if (posts.length === 0) {
    container.append(h('div', { class: 'empty-message' }, emptyContent));
    return;
  }
  posts.slice(0, view.limit).forEach((post) => container.append(renderPostCard(post)));
  container.append(
    renderMoreButton(posts.length, view.limit, 'もっと見る', () => {
      view.limit += PAGE_SIZE;
      refresh();
    }),
  );
}

// ===== 画面：S-01 ログイン =====
function renderLogin(app) {
  const alert = h('p', { class: 'form-alert hidden' });
  const email = h('input', { type: 'email', id: 'login-email', autocomplete: 'username', required: true });
  const password = h('input', { type: 'password', id: 'login-password', autocomplete: 'current-password', required: true });

  const form = h(
    'form',
    { novalidate: true },
    alert,
    h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'メールアドレス'), email),
    h('label', { class: 'field' }, h('span', { class: 'field-label' }, 'パスワード'), password),
    h('button', { type: 'submit', class: 'button primary full' }, 'ログイン'),
  );
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const user = db.users.find((u) => u.email === email.value.trim().toLowerCase());
    if (!user || user.password !== password.value) {
      // どちらが違うかは教えない
      alert.textContent = 'メールアドレスまたはパスワードが正しくありません';
      alert.classList.remove('hidden');
      password.value = '';
      password.focus();
      return;
    }
    currentUserId = user.id;
    toast(`${user.displayName}さんとしてログインしました`);
    location.hash = '#/';
  });

  const demo = h(
    'div',
    { class: 'demo-accounts' },
    h('p', null, 'デモ用アカウント（パスワードはすべて password123）。クリックで入力されます。'),
    h(
      'ul',
      null,
      db.users.slice(0, 6).map((user) =>
        h(
          'li',
          null,
          h(
            'button',
            {
              type: 'button',
              onclick: () => {
                email.value = user.email;
                password.value = 'password123';
              },
            },
            `${user.email}`,
          ),
          `（${user.displayName}）`,
        ),
      ),
    ),
  );

  app.append(
    h(
      'div',
      { class: 'auth-page' },
      h('h1', { class: 'auth-title' }, 'SNSアプリにログイン'),
      form,
      h('p', { class: 'auth-switch' }, 'アカウントをお持ちでない方は ', h('a', { href: '#/signup' }, '新規登録はこちら')),
      demo,
    ),
  );
}

// ===== 画面：S-02 新規登録 =====
function validateSignup(values) {
  const errors = {};
  if (!/^[A-Za-z0-9_]{4,15}$/.test(values.username)) {
    errors.username = 'ユーザー名は半角英数字と_で4〜15文字で入力してください';
  } else if (findUserByUsername(values.username)) {
    errors.username = 'このユーザー名はすでに使われています';
  }
  const nameLength = countChars(values.displayName.trim());
  if (nameLength < 1 || nameLength > DISPLAY_NAME_MAX) errors.displayName = '表示名は1〜50文字で入力してください';
  const email = values.email.trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 255) {
    errors.email = 'メールアドレスの形式が正しくありません';
  } else if (db.users.some((u) => u.email === email)) {
    errors.email = 'このメールアドレスはすでに登録されています';
  }
  if (values.password.length < PASSWORD_MIN) errors.password = 'パスワードは8文字以上で入力してください';
  else if (values.password.length > PASSWORD_MAX) errors.password = 'パスワードは72文字以内で入力してください';
  if (values.passwordConfirm !== values.password) errors.passwordConfirm = 'パスワードが一致しません';
  return errors;
}

function renderSignup(app) {
  const fields = [
    { name: 'username', label: 'ユーザー名', type: 'text', prefix: '@', autocomplete: 'username', maxlength: 15 },
    { name: 'displayName', label: '表示名', type: 'text', autocomplete: 'nickname' },
    { name: 'email', label: 'メールアドレス', type: 'email', autocomplete: 'email' },
    { name: 'password', label: 'パスワード', type: 'password', autocomplete: 'new-password' },
    { name: 'passwordConfirm', label: 'パスワード（確認）', type: 'password', autocomplete: 'new-password' },
  ];
  const inputs = {};
  const errorEls = {};
  const fieldEls = {};
  const form = h('form', { novalidate: true });
  for (const field of fields) {
    inputs[field.name] = h('input', {
      type: field.type,
      id: `signup-${field.name}`,
      autocomplete: field.autocomplete,
      maxlength: field.maxlength,
    });
    errorEls[field.name] = h('p', { class: 'field-error' });
    fieldEls[field.name] = h(
      'label',
      { class: 'field' },
      h('span', { class: 'field-label' }, field.label),
      field.prefix ? h('span', { class: 'input-with-prefix' }, field.prefix, inputs[field.name]) : inputs[field.name],
      errorEls[field.name],
    );
    form.append(fieldEls[field.name]);
  }
  form.append(h('button', { type: 'submit', class: 'button primary full' }, '登録する'));

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const values = Object.fromEntries(Object.entries(inputs).map(([key, input]) => [key, input.value]));
    const errors = validateSignup(values);
    for (const field of fields) {
      errorEls[field.name].textContent = errors[field.name] || '';
      fieldEls[field.name].classList.toggle('has-error', Boolean(errors[field.name]));
    }
    if (Object.keys(errors).length > 0) return;

    const user = {
      id: nextId.user++,
      username: values.username,
      displayName: values.displayName.trim(),
      email: values.email.trim().toLowerCase(),
      password: values.password,
      bio: '',
      iconUrl: null,
      createdAt: Date.now(),
    };
    db.users.push(user);
    currentUserId = user.id; // 登録後はそのままログイン状態にする
    toast('アカウントを作成しました');
    location.hash = '#/';
  });

  app.append(
    h(
      'div',
      { class: 'auth-page' },
      h('h1', { class: 'auth-title' }, 'アカウント作成'),
      form,
      h('p', { class: 'auth-switch' }, h('a', { href: '#/login' }, 'ログインはこちら')),
    ),
  );
}

// ===== 画面：S-03 タイムライン =====
function renderTimeline(app, tab) {
  const posts =
    tab === 'all'
      ? [...db.posts].sort(byNewest)
      : db.posts.filter((p) => p.userId === currentUserId || isFollowing(currentUserId, p.userId)).sort(byNewest);

  app.append(
    h(
      'div',
      { class: 'page-header' },
      h('h1', { class: 'page-title' }, 'ホーム'),
      h('button', { type: 'button', class: 'button primary', id: 'open-post-modal', onclick: () => openPostModal('create') }, '✏ 投稿する'),
    ),
    h(
      'nav',
      { class: 'tabs' },
      h('a', { href: '#/', class: 'tab' + (tab === 'following' ? ' active' : '') }, 'フォロー中'),
      h('a', { href: '#/all', class: 'tab' + (tab === 'all' ? ' active' : '') }, '全体'),
    ),
  );
  const list = h('section', { id: 'post-list' });
  const empty =
    tab === 'all'
      ? ['まだ誰も投稿していません。最初の投稿をしてみましょう']
      : ['まだ投稿がありません。『全体』タブや', h('a', { href: '#/search' }, 'ユーザー検索'), 'で気になる人を探してフォローしてみましょう'];
  renderPostList(list, posts, empty);
  app.append(list);
}

// ===== 画面：S-06 投稿詳細 =====
function renderBackHeader(title, sub) {
  return h(
    'div',
    { class: 'page-header' },
    h(
      'a',
      {
        href: '#/',
        class: 'back-link',
        'aria-label': '戻る',
        onclick: (e) => {
          if (history.length > 1) {
            e.preventDefault();
            history.back();
          }
        },
      },
      '←',
    ),
    h('div', null, h('h1', { class: 'page-title' }, title), sub ? h('div', { class: 'meta' }, sub) : null),
  );
}

function renderPostDetail(app, postId, focus) {
  app.append(renderBackHeader('投稿'));
  const post = findPostById(postId);
  if (!post) {
    app.append(h('div', { class: 'empty-message' }, 'この投稿は見つかりません'));
    return;
  }
  app.append(renderPostCard(post, { detail: true }));

  // コメント入力欄
  const textarea = h('textarea', { id: 'comment-input', rows: 2, placeholder: 'コメントを書く...', value: view.commentDraft || '' });
  const counter = h('span', { class: 'char-counter' });
  const error = h('p', { class: 'form-error' });
  const submit = h('button', { type: 'button', class: 'button primary', id: 'comment-submit' }, 'コメントする');
  const update = () => {
    view.commentDraft = textarea.value;
    const length = countChars(textarea.value.trim());
    counter.textContent = `${length} / ${COMMENT_MAX}`;
    counter.classList.toggle('over', length > COMMENT_MAX);
    submit.disabled = length === 0 || length > COMMENT_MAX;
  };
  textarea.addEventListener('input', update);
  submit.addEventListener('click', () => {
    const content = textarea.value.trim();
    const length = countChars(content);
    if (length < 1 || length > COMMENT_MAX) {
      error.textContent = 'コメントは1〜280文字で入力してください';
      return;
    }
    if (!findPostById(postId)) {
      toast('この投稿は削除されています', true);
      return;
    }
    db.comments.push({ id: nextId.comment++, postId, userId: currentUserId, content, createdAt: Date.now() });
    view.commentDraft = '';
    view.commentLimit = Math.max(view.commentLimit, commentCount(postId)); // 末尾に追加したコメントが見えるようにする
    refresh();
    toast('コメントしました');
  });
  update();
  app.append(
    h(
      'div',
      { class: 'comment-form' },
      renderAvatar(me()),
      h('div', { class: 'form-main' }, textarea, error, h('div', { class: 'form-footer' }, counter, submit)),
    ),
  );

  // コメント一覧（古い順）
  const comments = db.comments.filter((c) => c.postId === postId).sort((a, b) => a.createdAt - b.createdAt || a.id - b.id);
  const list = h('section', { id: 'comment-list' });
  if (comments.length === 0) {
    list.append(h('div', { class: 'empty-message' }, 'まだコメントはありません'));
  } else {
    comments.slice(0, view.commentLimit).forEach((comment) => list.append(renderComment(comment)));
    list.append(
      renderMoreButton(comments.length, view.commentLimit, 'さらに表示', () => {
        view.commentLimit += PAGE_SIZE;
        refresh();
      }),
    );
  }
  app.append(list);

  if (focus === 'comment') textarea.focus();
}

function renderComment(comment) {
  const author = findUserById(comment.userId);
  const deleteButton =
    comment.userId === currentUserId
      ? h('button', { type: 'button', class: 'link-button', onclick: () => deleteComment(comment.id) }, '削除')
      : null;
  return h(
    'div',
    { class: 'comment-item', dataset: { commentId: comment.id } },
    renderAvatarLink(author, 'small'),
    h(
      'div',
      { class: 'comment-body' },
      h('div', { class: 'comment-head' }, renderNameLink(author), h('span', { class: 'meta' }, `· ${formatRelativeTime(comment.createdAt)}`), deleteButton),
      h('p', { class: 'comment-content' }, comment.content),
    ),
  );
}

async function deleteComment(commentId) {
  const ok = await confirmDialog('このコメントを削除しますか？', '削除する');
  if (!ok) return;
  db.comments = db.comments.filter((c) => c.id !== commentId);
  toast('コメントを削除しました');
  refresh();
}

// ===== 画面：S-07 プロフィール =====
function renderProfile(app, username) {
  const user = findUserByUsername(username);
  if (!user) {
    app.append(renderBackHeader('プロフィール'), h('div', { class: 'empty-message' }, 'このアカウントは存在しません'));
    return;
  }
  const posts = db.posts.filter((p) => p.userId === user.id).sort(byNewest);
  const isMe = user.id === currentUserId;
  const action = isMe
    ? h('a', { href: '#/settings/profile', class: 'button', id: 'edit-profile-link' }, 'プロフィールを編集')
    : renderFollowButton(user);

  app.append(
    renderBackHeader(user.displayName, `${posts.length}件の投稿`),
    h('div', { class: 'profile-cover' }),
    h(
      'div',
      { class: 'profile-info' },
      h('div', { class: 'profile-top' }, renderAvatar(user, 'large'), action),
      h('h2', { class: 'profile-name' }, user.displayName),
      h('div', { class: 'username' }, `@${user.username}`),
      user.bio ? h('p', { class: 'profile-bio' }, user.bio) : null,
      h(
        'div',
        { class: 'profile-counts' },
        h('a', { href: `${userPath(user)}/following`, id: 'following-count-link' }, h('strong', null, followingCount(user.id)), ' フォロー中'),
        h('a', { href: `${userPath(user)}/followers`, id: 'follower-count-link' }, h('strong', null, followerCount(user.id)), ' フォロワー'),
      ),
    ),
    h('h2', { class: 'section-heading' }, '投稿'),
  );
  const list = h('section', { id: 'post-list' });
  renderPostList(list, posts, isMe ? 'まだ投稿がありません。最初の投稿をしてみましょう' : 'まだ投稿がありません');
  app.append(list);
}

// ===== 画面：S-08 プロフィール編集 =====
function renderSettings(app) {
  const user = me();
  const draft = { iconUrl: user.iconUrl, displayName: user.displayName, bio: user.bio };
  const isDirty = () => draft.iconUrl !== user.iconUrl || draft.displayName !== user.displayName || draft.bio !== user.bio;

  const avatarSlot = h('div', null, renderAvatar(user, 'large'));
  const iconError = h('p', { class: 'field-error' });
  const fileInput = h('input', { type: 'file', accept: IMAGE_TYPES.join(','), hidden: true });
  fileInput.addEventListener('change', async () => {
    const file = fileInput.files[0];
    fileInput.value = '';
    if (!file) return;
    const message = validateImageFile(file);
    iconError.textContent = message || '';
    if (message) return;
    draft.iconUrl = await readAsDataUrl(file);
    avatarSlot.replaceChildren(renderAvatar({ ...user, iconUrl: draft.iconUrl }, 'large'));
  });

  const nameInput = h('input', { type: 'text', id: 'settings-display-name', value: draft.displayName });
  const nameError = h('p', { class: 'field-error' });
  const bioInput = h('textarea', { id: 'settings-bio', rows: 4, value: draft.bio });
  const bioCounter = h('div', { class: 'char-counter' });
  const bioError = h('p', { class: 'field-error' });
  const updateBio = () => {
    draft.bio = bioInput.value;
    const length = countChars(bioInput.value);
    bioCounter.textContent = `${length} / ${BIO_MAX}`;
    bioCounter.classList.toggle('over', length > BIO_MAX);
  };
  nameInput.addEventListener('input', () => (draft.displayName = nameInput.value));
  bioInput.addEventListener('input', updateBio);
  updateBio();

  const cancel = async () => {
    if (isDirty() && !(await confirmDialog('変更は保存されません。よろしいですか？', '破棄する'))) return;
    location.hash = userPath(user);
  };
  const save = () => {
    const name = draft.displayName.trim();
    const nameLength = countChars(name);
    nameError.textContent = nameLength < 1 || nameLength > DISPLAY_NAME_MAX ? '表示名は1〜50文字で入力してください' : '';
    bioError.textContent = countChars(draft.bio) > BIO_MAX ? '自己紹介は160文字以内で入力してください' : '';
    if (nameError.textContent || bioError.textContent) return;
    user.displayName = name;
    user.bio = draft.bio;
    user.iconUrl = draft.iconUrl;
    toast('プロフィールを更新しました');
    location.hash = userPath(user);
  };

  app.append(
    renderBackHeader('プロフィールを編集'),
    h(
      'div',
      { class: 'settings-form' },
      h(
        'div',
        { class: 'icon-edit' },
        avatarSlot,
        h('div', null, h('label', { class: 'button' }, 'アイコン画像を変更', fileInput), iconError),
      ),
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, '表示名'), nameInput, nameError),
      h('label', { class: 'field' }, h('span', { class: 'field-label' }, '自己紹介'), bioInput, bioCounter, bioError),
      h('p', { class: 'meta' }, `@${user.username} とメールアドレスは変更できません。`),
      h(
        'div',
        { class: 'form-buttons' },
        h('button', { type: 'button', class: 'button', onclick: cancel }, 'キャンセル'),
        h('button', { type: 'button', class: 'button primary', id: 'settings-save', onclick: save }, '保存する'),
      ),
    ),
  );
}

// ===== 画面：S-09 フォロー中・フォロワー一覧 =====
function renderFollowList(app, username, kind) {
  const user = findUserByUsername(username);
  if (!user) {
    app.append(renderBackHeader('フォロー'), h('div', { class: 'empty-message' }, 'このアカウントは存在しません'));
    return;
  }
  const relations =
    kind === 'following'
      ? db.follows.filter((f) => f.followerId === user.id).map((f) => ({ user: findUserById(f.followeeId), at: f.createdAt }))
      : db.follows.filter((f) => f.followeeId === user.id).map((f) => ({ user: findUserById(f.followerId), at: f.createdAt }));
  relations.sort((a, b) => b.at - a.at); // フォローした日時の新しい順

  app.append(
    renderBackHeader(user.displayName, `@${user.username}`),
    h(
      'nav',
      { class: 'tabs' },
      h('a', { href: `${userPath(user)}/following`, class: 'tab' + (kind === 'following' ? ' active' : '') }, 'フォロー中'),
      h('a', { href: `${userPath(user)}/followers`, class: 'tab' + (kind === 'followers' ? ' active' : '') }, 'フォロワー'),
    ),
  );
  const list = h('section', { id: 'user-list' });
  if (relations.length === 0) {
    list.append(h('div', { class: 'empty-message' }, kind === 'following' ? 'まだ誰もフォローしていません' : 'まだフォロワーはいません'));
  } else {
    relations.slice(0, view.limit).forEach(({ user: u }) => list.append(renderUserRow(u)));
    list.append(
      renderMoreButton(relations.length, view.limit, 'もっと見る', () => {
        view.limit += PAGE_SIZE;
        refresh();
      }),
    );
  }
  app.append(list);
}

// ===== 画面：S-10 ユーザー検索 =====

// 前後の空白と、先頭の @ を1つ取り除く
function normalizeKeyword(raw) {
  return raw.trim().replace(/^@/, '').trim();
}

// ①ユーザー名が完全一致 → ②ユーザー名が前方一致 → ③それ以外。同じ順位はユーザー名の昇順
function searchUsers(keyword) {
  if (!keyword) return [...db.users].sort(byNewest);
  const q = keyword.toLowerCase();
  const rank = (user) => {
    const name = user.username.toLowerCase();
    if (name === q) return 0;
    if (name.startsWith(q)) return 1;
    return 2;
  };
  return db.users
    .filter((user) => user.username.toLowerCase().includes(q) || user.displayName.toLowerCase().includes(q))
    .sort((a, b) => rank(a) - rank(b) || a.username.localeCompare(b.username));
}

let searchTimer = null;
function renderSearch(app) {
  const input = h('input', {
    type: 'text', // type=search だとブラウザ標準の×も出て、自前の×と二重になるため
    id: 'search-input',
    placeholder: '@ユーザー名 または 表示名で検索',
    maxlength: SEARCH_MAX,
    value: view.q,
    autocomplete: 'off',
  });
  const clear = h('button', { type: 'button', class: 'icon-button', 'aria-label': '入力をクリア' }, '×');
  const results = h('section', { id: 'search-results' });

  const runSearch = (raw) => {
    clearTimeout(searchTimer);
    view.q = raw;
    view.limit = PAGE_SIZE;
    // 履歴を増やさずに URL を書き換える（リロード・共有で同じ結果が出るように）
    history.replaceState(null, '', raw ? `#/search?q=${encodeURIComponent(raw)}` : '#/search');
    renderSearchResults(results);
  };
  input.addEventListener('input', () => {
    // 入力が止まってから 0.3 秒後に検索する（1文字ごとには検索しない）
    clearTimeout(searchTimer);
    results.replaceChildren(h('div', { class: 'loading' }, '検索中…'));
    searchTimer = setTimeout(() => runSearch(input.value), SEARCH_DELAY_MS);
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      runSearch(input.value);
    }
  });
  clear.addEventListener('click', () => {
    input.value = '';
    runSearch('');
    input.focus();
  });

  app.append(
    h('div', { class: 'page-header' }, h('h1', { class: 'page-title' }, 'ユーザーを探す')),
    h('div', { class: 'search-box' }, input, clear),
    results,
  );
  renderSearchResults(results);
}

function renderSearchResults(container) {
  const keyword = normalizeKeyword(view.q);
  const users = searchUsers(keyword);
  container.replaceChildren(h('h2', { class: 'section-heading' }, keyword ? `「${view.q.trim()}」の検索結果` : '最近参加したユーザー'));
  if (users.length === 0) {
    container.append(h('div', { class: 'empty-message' }, `「${view.q.trim()}」に一致するユーザーは見つかりませんでした`));
    return;
  }
  users.slice(0, view.limit).forEach((user) => container.append(renderUserRow(user)));
  container.append(
    renderMoreButton(users.length, view.limit, 'もっと見る', () => {
      view.limit += PAGE_SIZE;
      renderSearchResults(container);
    }),
  );
}

// ===== S-04 投稿作成 / S-05 投稿編集 モーダル =====
const postModal = { mode: 'create', postId: null, images: [], initialText: '' };
const $ = (id) => document.getElementById(id);

function openPostModal(mode, postId = null) {
  postModal.mode = mode;
  postModal.postId = postId;
  postModal.images = [];
  const post = mode === 'edit' ? findPostById(postId) : null;
  if (mode === 'edit' && !post) {
    toast('この投稿はすでに削除されています', true);
    refresh();
    return;
  }
  postModal.initialText = post ? post.content : '';
  $('post-modal-title').textContent = mode === 'edit' ? '投稿を編集' : '新しい投稿';
  $('post-modal-submit').textContent = mode === 'edit' ? '保存する' : '投稿する';
  $('post-modal-text').value = postModal.initialText;
  $('post-modal-error').textContent = '';
  $('post-modal-image-label').classList.toggle('hidden', mode === 'edit'); // 編集では画像を変えられない
  $('post-modal-avatar').replaceChildren(renderAvatar(me()));
  renderModalImages();
  updatePostModal();
  $('post-modal').classList.remove('hidden');
  $('post-modal-text').focus();
}

function renderModalImages() {
  const container = $('post-modal-images');
  if (postModal.mode === 'edit') {
    const post = findPostById(postModal.postId);
    container.replaceChildren(
      ...post.images.map((image) => h('div', { class: 'compose-image' }, h('img', { src: image.url, alt: '' }))),
      post.images.length ? h('p', { class: 'compose-note' }, '画像は変更できません（本文のみ編集できます）') : '',
    );
    return;
  }
  container.replaceChildren(
    ...postModal.images.map((url, index) =>
      h(
        'div',
        { class: 'compose-image' },
        h('img', { src: url, alt: `添付画像${index + 1}` }),
        h(
          'button',
          {
            type: 'button',
            class: 'remove',
            'aria-label': '画像を外す',
            onclick: () => {
              postModal.images.splice(index, 1);
              renderModalImages();
              updatePostModal();
            },
          },
          '×',
        ),
      ),
    ),
  );
}

function updatePostModal() {
  const length = countChars($('post-modal-text').value.trim());
  const counter = $('post-modal-counter');
  counter.textContent = `${length} / ${POST_MAX}`;
  counter.classList.toggle('over', length > POST_MAX);
  const imageCount = postModal.mode === 'edit' ? findPostById(postModal.postId).images.length : postModal.images.length;
  // 本文も画像もないとき、280文字を超えたときは押せない
  $('post-modal-submit').disabled = length > POST_MAX || (length === 0 && imageCount === 0);
  $('post-modal-image-label').classList.toggle('disabled', postModal.images.length >= IMAGE_MAX_COUNT);
}

function isPostModalDirty() {
  return $('post-modal-text').value !== postModal.initialText || postModal.images.length > 0;
}

async function closePostModal({ force = false } = {}) {
  if (!force && isPostModalDirty()) {
    const ok = await confirmDialog('入力中の内容は破棄されます。よろしいですか？', '破棄する');
    if (!ok) return;
  }
  $('post-modal').classList.add('hidden');
}

async function addModalImages(files) {
  const error = $('post-modal-error');
  error.textContent = '';
  for (const file of files) {
    if (postModal.images.length >= IMAGE_MAX_COUNT) {
      error.textContent = '画像は4枚まで添付できます';
      break;
    }
    const message = validateImageFile(file);
    if (message) {
      error.textContent = message;
      continue;
    }
    postModal.images.push(await readAsDataUrl(file));
  }
  renderModalImages();
  updatePostModal();
}

function submitPostModal() {
  const content = $('post-modal-text').value.trim();
  const submit = $('post-modal-submit');
  submit.disabled = true;
  submit.textContent = postModal.mode === 'edit' ? '保存中…' : '投稿中…';

  // 通信している雰囲気を出すために少し待つ
  setTimeout(() => {
    if (postModal.mode === 'create') {
      db.posts.push({
        id: nextId.post++,
        userId: currentUserId,
        content,
        images: postModal.images.map((url, i) => ({ url, sortOrder: i + 1 })),
        createdAt: Date.now(),
        editedAt: null,
      });
      closePostModal({ force: true });
      toast('投稿しました');
      const onOwnProfile = currentRoute.name === 'profile' && findUserByUsername(currentRoute.username)?.id === currentUserId;
      if (currentRoute.name === 'timeline' || onOwnProfile) {
        refresh();
        window.scrollTo(0, 0);
      } else {
        location.hash = '#/';
      }
    } else {
      const post = findPostById(postModal.postId);
      if (post && post.content !== content) {
        post.content = content;
        post.editedAt = Date.now();
        toast('投稿を更新しました');
      }
      closePostModal({ force: true });
      refresh();
    }
  }, 300);
}

function setupPostModal() {
  $('post-modal-text').addEventListener('input', updatePostModal);
  $('post-modal-file').addEventListener('change', (e) => {
    const files = [...e.target.files];
    e.target.value = '';
    addModalImages(files);
  });
  $('post-modal-image-label').addEventListener('click', (e) => {
    if (postModal.images.length >= IMAGE_MAX_COUNT) {
      e.preventDefault();
      $('post-modal-error').textContent = '画像は4枚まで添付できます';
    }
  });
  $('post-modal-submit').addEventListener('click', submitPostModal);
  $('post-modal-close').addEventListener('click', () => closePostModal());
  $('post-modal').addEventListener('click', (e) => {
    if (e.target === $('post-modal')) closePostModal();
  });
}

// ===== ルーティング =====
const PUBLIC_ROUTES = ['login', 'signup'];

function parseRoute() {
  const raw = location.hash.replace(/^#/, '') || '/';
  const [path, query = ''] = raw.split('?');
  const params = new URLSearchParams(query);
  const seg = path.split('/').filter(Boolean).map(decodeURIComponent);

  if (seg.length === 0) return { name: 'timeline', tab: 'following' };
  if (seg[0] === 'all' && seg.length === 1) return { name: 'timeline', tab: 'all' };
  if (seg[0] === 'login' && seg.length === 1) return { name: 'login' };
  if (seg[0] === 'signup' && seg.length === 1) return { name: 'signup' };
  if (seg[0] === 'posts' && seg.length === 2) return { name: 'post', postId: Number(seg[1]), focus: params.get('focus') };
  if (seg[0] === 'users' && seg.length === 2) return { name: 'profile', username: seg[1] };
  if (seg[0] === 'users' && seg.length === 3 && ['following', 'followers'].includes(seg[2])) {
    return { name: 'follows', username: seg[1], kind: seg[2] };
  }
  if (seg[0] === 'settings' && seg[1] === 'profile' && seg.length === 2) return { name: 'settings' };
  if (seg[0] === 'search' && seg.length === 1) return { name: 'search', q: params.get('q') || '' };
  return { name: 'notFound' };
}

// 画面ごとの一時的な状態（表示件数・検索語など）。別の画面へ移動したら初期化する
const newView = () => ({ limit: PAGE_SIZE, commentLimit: PAGE_SIZE, q: null, commentDraft: '' });
let view = newView();
let currentRoute = { name: 'login' };

function updateHeader() {
  const user = me();
  $('app-header').classList.toggle('hidden', !user);
  if (!user) return;
  $('nav-profile').setAttribute('href', userPath(user));
  const onOwnProfile = currentRoute.name === 'profile' && findUserByUsername(currentRoute.username)?.id === user.id;
  document.querySelector('[data-nav="home"]').classList.toggle('active', currentRoute.name === 'timeline');
  $('nav-profile').classList.toggle('active', onOwnProfile);
}

function render() {
  const route = parseRoute();
  const isPublic = PUBLIC_ROUTES.includes(route.name);
  // 未ログインならログイン画面へ、ログイン済みならログイン・新規登録画面は開かせない
  if (!me() && !isPublic) {
    location.replace('#/login');
    return;
  }
  if (me() && isPublic) {
    location.replace('#/');
    return;
  }
  currentRoute = route;
  if (route.name === 'search' && view.q === null) view.q = route.q;
  updateHeader();

  const app = $('app');
  app.replaceChildren();
  switch (route.name) {
    case 'login':
      renderLogin(app);
      break;
    case 'signup':
      renderSignup(app);
      break;
    case 'timeline':
      renderTimeline(app, route.tab);
      break;
    case 'post':
      renderPostDetail(app, route.postId, route.focus);
      break;
    case 'profile':
      renderProfile(app, route.username);
      break;
    case 'settings':
      renderSettings(app);
      break;
    case 'follows':
      renderFollowList(app, route.username, route.kind);
      break;
    case 'search':
      renderSearch(app);
      break;
    default:
      app.append(renderBackHeader('ページが見つかりません'), h('div', { class: 'empty-message' }, 'お探しのページは見つかりませんでした'));
  }
}

// 同じ画面のまま描き直す（表示件数やスクロール位置は保つ）
function refresh() {
  const y = window.scrollY;
  render();
  window.scrollTo(0, y);
}

// ===== 起動 =====
function setupGlobalEvents() {
  window.addEventListener('hashchange', () => {
    view = newView();
    render();
    window.scrollTo(0, 0);
  });
  document.addEventListener('click', closeMenus);

  $('logout-button').addEventListener('click', () => {
    currentUserId = null;
    toast('ログアウトしました');
    location.hash = '#/login';
  });
  $('header-search-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const value = $('header-search-input').value;
    $('header-search-input').value = '';
    location.hash = value.trim() ? `#/search?q=${encodeURIComponent(value)}` : '#/search';
  });

  $('confirm-ok').addEventListener('click', () => closeConfirm(true));
  $('confirm-cancel').addEventListener('click', () => closeConfirm(false));
  $('lightbox').addEventListener('click', () => $('lightbox').classList.add('hidden'));

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!$('confirm-dialog').classList.contains('hidden')) closeConfirm(false);
    else if (!$('lightbox').classList.contains('hidden')) $('lightbox').classList.add('hidden');
    else if (!$('post-modal').classList.contains('hidden')) closePostModal();
    else closeMenus();
  });
}

seed();
setupPostModal();
setupGlobalEvents();
render();
