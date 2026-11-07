# パフォーマンステスト計画書

[非機能要件](non-functional-requirements.md)の「性能」を満たしているかを、負荷をかけて確かめる計画。機能が正しく動くかを確かめる自動テストは[テスト計画書](test-plan.md)で扱い、この計画書では「速さ」と「たくさん使われても壊れないか」を扱う。

**パフォーマンステストは CI で毎回動かさない。** 必要なときに手で動かす（「8. 動かし方」）。時間がかかり、動かす環境によって結果が変わるため。

## 1. 目的と合格の基準

| 確かめること | 基準 | 合否 |
|---|---|---|
| 想定の規模（数百ユーザー・数千投稿）で、表示が1秒以内に終わるか | 同時に **50人** が操作して、API の応答時間が **p95 < 500ms**。画面の描画に残りの時間を残すため、非機能要件の「1秒以内」より厳しくする | 付ける |
| エラーが出ないか | エラー率（5xx と通信の失敗）が **1% 未満** | 付ける |
| 想定の10倍のデータで、どこから遅くなるか | 限界の人数と、遅くなる API・SQL を記録する | 付けない |
| 長く動かしても劣化しないか | 1時間動かして、応答時間とヒープの使用量が増え続けない（EC2 では `-Xmx512m`。[インフラ構成](infrastructure.md)） | 付ける |

- p95 は「95% のリクエストがこの時間以内に返った」という意味。平均は一部の遅いリクエストを隠すので使わない
- 基準は k6 の `thresholds` に書き、超えたら k6 が失敗で終わるようにする（目で見て判断しない）
- 応答時間の基準は、[ログ・監視の運用方針](observability.md)の SLO（API が1秒以内に返った割合）・アラート（A4：p95 が1秒を超える）と同じ考え方にそろえる

## 2. 使う道具

| 道具 | 使い方 | 選んだ理由 |
|---|---|---|
| **k6** | 利用者の操作を TypeScript で書き、同時に何人も動かして応答時間を測る | フロントと同じ TypeScript で書ける（k6 は `.ts` をそのまま実行する。型チェックは `perf/` で `npm run typecheck`）。`thresholds` で合否を自動で判定できる。Docker イメージ（`grafana/k6`）で動くので Mac に入れなくてよい。版は導入するときの最新の安定版に固定する（[技術スタック](tech-stack.md)の決め方） |
| **`EXPLAIN (ANALYZE, BUFFERS)`** | 遅い SQL の実行計画を見る（インデックスが使われているか、何行読んだか） | このアプリで遅くなる原因は、ほとんど SQL になると考えられるため |
| **`pg_stat_statements`** | 負荷をかけている間に、合計時間が長い SQL を順に並べる | どの SQL から直すべきかが分かる。PostgreSQL の拡張で、専用の DB でだけ有効にする |
| **Actuator の metrics** | JVM のヒープ・GC、DB の接続プール（HikariCP）の待ちを見る | ふだんは `health` だけを公開している。性能テストのときだけ、環境変数で `metrics` も公開する（「8. 動かし方」） |

選ばなかったもの：

- **Gatling**：Java で書け、Gradle で動く。ただ、ビルドの設定が増え、ふだんのテスト（`./gradlew check`）と分ける手間がかかる
- **JMeter**：GUI と XML が中心で、Git で差分を見づらい

## 3. 実行する環境

2段階で進める。

| 段階 | 環境 | 目的 | 結果の扱い |
|---|---|---|---|
| **1. ローカル（先にやる）** | docker compose の PostgreSQL（専用の DB `sns_perf`）＋ バックエンドの jar を本番と同じ JVM の設定（`-Xms256m -Xmx512m`）で起動 ＋ k6（Docker） | SQL・インデックスの問題を、お金をかけずに見つける | Mac は t3.micro より速いので、**変更の前と後を比べる**ための値として扱う。合否は参考にとどめる |
| **2. AWS（apply のあと）** | CloudFront（アプリ用）→ ALB → EC2（t3.micro）→ RDS（db.t3.micro） | 本番と同じ構成で、基準を満たすかを確かめる | この段階の結果で合否を決める |

- 開発用の DB（`sns`）は使わない。テストデータを大量に入れるので、**専用の DB `sns_perf`** を同じ PostgreSQL のコンテナに作る（自動テストが使い捨てのコンテナを使うのと同じく、開発用のデータを汚さないため）
- 画像のアップロードは、LocalStack では本物の S3 の速さが分からないので、段階 2 でだけ確かめる
- 段階 2 は、短い時間（数十分）だけ動かし、終わったら止める（[インフラ構成](infrastructure.md)の起動・停止）。CloudFront の負荷テストの条件に収まるよう、負荷は「同時に50人」程度から大きく上げない
- 段階 2 では、ALB に直接ではなく CloudFront の URL に負荷をかける（ALB は CloudFront からだけ受け付けるため）

## 4. テストデータ

`scripts/seed.sh` は API を通してデータを作るので、何万件も作るのには向かない。**SQL の `generate_series` で一気に作る**スクリプトを別に用意する。

- パスワードは、前もって作った BCrypt のハッシュ1つを全員で使い回す（1人ずつハッシュを作ると時間がかかるため）。ログインはすべて同じパスワードでできる
- 投稿日時は、過去30日にばらつかせる（タイムラインの並び替えとカーソルのページングを、本物に近い形で動かすため）
- 画像のデータ（`post_images`・アイコン）は入れない。画像の表示は CloudFront と S3 が返すので、API の速さには関係しない

| 規模 | ユーザー | 投稿 | フォロー | いいね | コメント | 使う場面 |
|---|---|---|---|---|---|---|
| **基準**（非機能要件の規模） | 500 | 5,000 | 1人平均50 | 5万 | 1万 | 合否を決める |
| **10倍** | 5,000 | 5万 | 1人平均100 | 50万 | 10万 | 限界と、遅くなる箇所を探す |

偏りのあるデータも入れる。本物の SNS では、人気のある人や投稿にデータが集まり、そこで遅くなりやすいため。

| 偏りのあるデータ | 基準 | 10倍 |
|---|---|---|
| たくさんフォローしているユーザー `perf00001`（フォロー中のタイムラインが一番重くなる人） | 499人（自分以外の全員） | 500人 |
| フォロワーの多いユーザー `perf00002` | 499人（自分以外の全員） | 1,000人 |
| いいねの多い投稿（投稿 ID 1。`perf00002` の投稿） | 500件（全員） | 1,000件 |
| コメントの多い投稿（投稿 ID 2。`perf00002` の投稿） | 500件 | 500件 |

基準の規模はユーザーが500人しかいないので、フォロワーといいねは全員までにしている（同じ人は2回フォロー・いいねできないため）。

### 作り方

```bash
docker compose up -d postgres
perf/setup-db.sh base   # 基準の規模（10倍は x10）
```

- `perf/setup-db.sh` は、docker compose の PostgreSQL の中に `sns_perf` を作り、Flyway（バックエンドと同じ版の Docker イメージ）でテーブルを作り、`perf/data/seed.sql` でデータを入れる。前のデータを消してから入れ直すので、規模を変えるときも同じコマンドでよい（基準は約20秒、10倍も約20秒）
- `seed.sql` は `sns_perf` 以外の DB では止まる（開発用の `sns` に間違って入れないため）
- 乱数の種を固定しているので、何度作り直しても同じデータになる（変更の前と後を、同じデータで比べるため）。日時だけは「実行した時刻から過去30日」なので、作り直すとずれる
- ユーザーは `perf00001`〜（メールアドレスは `perf00001@example.com`〜）、パスワードはすべて `password123`
- バックエンドを `sns_perf` につなぐには、環境変数 `POSTGRES_DB=sns_perf` を付けて起動する（`.env` より環境変数が優先される）

```bash
cd backend && POSTGRES_DB=sns_perf ./gradlew bootRun
```

## 5. 重点的に確かめる箇所

コードを読んで、遅くなりそうだと考えた箇所。測定ではここの `EXPLAIN ANALYZE` を必ず残す。

| 箇所 | 心配な点 |
|---|---|
| フォロー中のタイムライン（`PostMapper.xml` の `findFollowingTimeline`） | `p.user_id = 自分 OR p.user_id IN (フォロー先)` の形は、インデックス（`idx_posts_user_id_created_at`）を使いにくい。フォロー先が多い人ほど遅くなるおそれがある |
| 一覧のいいね数・コメント数（`selectWithAuthor`） | 1回の SQL にまとまっていて N+1 にはなっていないが、投稿ごとに `count(*)` の副問い合わせが動く。いいねが多い投稿で遅くなるおそれがある |
| ユーザー検索（`UserMapper.xml`） | `ILIKE '%…%'` は通常のインデックスを使えず、ユーザーを全件読む。[技術スタック](tech-stack.md)の「pg_trgm はユーザーが増えたときに追加する」を判断する材料にする |
| 新しい投稿の件数（`countNewInFollowingTimeline`・`countNewInAll`） | 画面を開いている全員が60秒ごとに呼ぶので、呼ばれる回数が一番多い |
| ログイン | BCrypt はわざと重く作られている。t3.micro では、ログインが集中すると CPU を使い切り、ほかの API まで遅くなるおそれがある |
| DB の接続プール | HikariCP の既定は10本。同時に50人のときに、接続の空き待ちが起きないか |

## 6. シナリオ

### 6.1 利用者の操作の割合

1人の利用者（k6 の VU）は、ログインしたあと、次の割合で操作を繰り返す。操作の間には、人が画面を読む時間（3〜10秒）を入れる。

| 操作 | 割合 | 呼ぶ API |
|---|---|---|
| タイムラインを見る（続きの読み込みを含む） | 40% | `GET /api/timeline`・`/api/timeline/all`（カーソル付きで2〜3ページ） |
| 投稿の詳細とコメントを見る | 20% | `GET /api/posts/{postId}`・`/api/posts/{postId}/comments` |
| プロフィールを見る | 15% | `GET /api/users/{username}`・`/api/users/{username}/posts`、フォロー一覧 |
| いいね・フォロー | 10% | `POST`・`DELETE` の `/api/posts/{postId}/likes`・`/api/users/{username}/follow` |
| ユーザーを検索する | 5% | `GET /api/users/search` |
| 投稿・コメントを書く | 5% | `POST /api/posts`（画像なし）・`/api/posts/{postId}/comments` |
| 新しい投稿の件数の確認 | 60秒ごと（全員） | `GET /api/timeline/new-count`・`/api/timeline/all/new-count` |

### 6.2 シナリオの種類

| 種類 | 内容 | 時間 | データ |
|---|---|---|---|
| **スモーク** | 1人で全部の操作を1回ずつ通す。スクリプトが正しく動くかの確認で、ほかのシナリオの前に必ず流す | 1分 | 基準 |
| **負荷** | 同時に50人が操作する。合否を判定する | 10分 | 基準 |
| **ストレス** | 人数を10人ずつ増やし、p95 が1秒を超える人数（限界）を探す | 15分 | 基準・10倍 |
| **耐久** | 負荷と同じ50人で長く動かす。アクセストークンの再発行（15分ごと。`POST /api/auth/refresh`）も含める | 60分 | 基準 |
| **ログイン集中** | ログインだけを集中させ、BCrypt が CPU をどれだけ使うかを見る | 5分 | 基準 |

## 7. 見る値と記録

| どこ | 見る値 |
|---|---|
| k6 | 応答時間（p50・p95・p99）、エラー率、1秒あたりの処理件数。API ごとに `tags` で分けて、どの API が遅いかが分かるようにする |
| DB | `pg_stat_statements` の合計時間の上位10件、「5. 重点的に確かめる箇所」の `EXPLAIN ANALYZE` |
| バックエンド | ヒープの使用量と GC の時間、HikariCP の接続の待ち（`hikaricp.connections.pending`） |
| AWS（段階 2） | EC2・RDS の CPU、ALB の応答時間（[ログ・監視の運用方針](observability.md)の A4 と同じ値） |

結果は `docs/performance-test-results.md` に追記していく。前回と比べられるように、毎回次のことを書く。

- 日付、コミット、環境（ローカル・AWS）、データの規模、シナリオ
- k6 の要約（API ごとの p95 とエラー率）、合否
- 遅かった SQL と、その `EXPLAIN ANALYZE` の要点
- 見つかった問題と、作った Issue

k6 の出力そのもの（JSON）は `perf/results/` に置き、Git には入れない。

## 8. 動かし方

```
perf/
  setup-db.sh perf/setup-db.sh base|x10  ← 専用の DB sns_perf を作り、テストデータを入れる（「4. テストデータ」）
  data/       テストデータを作る SQL（seed.sql。規模は setup-db.sh が渡す）、実行計画を見る SQL（explain.sql）
  scenarios/  k6 のスクリプト（smoke・load・stress・soak・login。「6.2 シナリオの種類」）
    lib/api.ts  API の呼び出し・利用者の操作（「6.1」の割合）・監視役（metrics の記録）・合格の基準（thresholds）
  run.sh      perf/run.sh <シナリオ> [接続先の URL]  ← Docker の k6（grafana/k6:2.3.0）で動かす
  db-report.sh perf/db-report.sh reset|top|explain  ← pg_stat_statements の集計・EXPLAIN (ANALYZE, BUFFERS)
  analyze.py  python3 perf/analyze.py <CSV>  ← k6 の結果を時間ごと（人数ごと）に集計する
  results/    k6 の結果の要約（JSON）と、リクエストごとの値（CSV。時刻つき）。Git には入れない
  package.json・tsconfig.json  シナリオの型チェック（TypeScript・@types/k6）
```

- 負荷・ストレス・耐久・ログイン集中では、監視役（1人）がバックエンドの metrics（ヒープ・GC の累計・CPU・接続プール）を定期的に読み、k6 の結果に `backend_*` という名前で残す。監視役のリクエストは、合格の基準に入れない（シナリオで分けている）
- シナリオは TypeScript で書く。k6 は型を取り除いて実行するだけで、型が合っているかは確かめないので、シナリオを直したら `cd perf && npm install && npm run typecheck` で確かめる（`npm install` は最初の1回だけ）。CI でも毎回確かめる（負荷をかける本体は CI では動かさない）
- k6 の要約は全体の値しか出さないので、人数ごとの p95（ストレス）や、時間がたつと遅くなるか・ヒープが増え続けるか（耐久）は `perf/analyze.py` で見る
- `pg_stat_statements` は docker compose の PostgreSQL で読み込み（`docker-compose.yml` の `command`）、拡張は `perf/setup-db.sh` が `sns_perf` にだけ作る。シナリオの前に `perf/db-report.sh reset`、あとに `perf/db-report.sh top`

### ローカルでの手順

```bash
# 1. データを入れる（「4. テストデータ」）
perf/setup-db.sh base

# 2. バックエンドを、本番と同じ JVM の設定で sns_perf につないで起動する（backend で実行。.env を読むため）
cd backend && ./gradlew bootJar
POSTGRES_DB=sns_perf MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics \
  java -Xms256m -Xmx512m -jar build/libs/backend-0.0.1-SNAPSHOT.jar

# 3. 別のターミナルで、スモーク → 負荷の順に流す（リポジトリのルートで）
perf/run.sh smoke
perf/run.sh load                     # 50人・10分。短く試すときは VUS=10 DURATION=1m perf/run.sh load
```

- k6 はコンテナの中で動くので、Mac のバックエンドには `http://host.docker.internal:8080` でつなぐ（`run.sh` の既定）。AWS では CloudFront の URL を2つ目の引数に渡す
- 合格の基準を満たさないと、k6 は 0 以外の終了コード（99）で終わる。スモークは1つでも失敗したら止まる
- 結果には、全部の API をまとめた値に加えて、API ごとの p95 が出る（`http_req_duration{name:GET /api/timeline}` など）。API ごとの値は合否に使わず、遅い API を見つけるために見る
- テストを流すとデータが少し増える（投稿・コメント・いいね・フォロー）。前回と同じデータで比べるときは、`perf/setup-db.sh` で入れ直す
- データを入れ直したときは、`perf/setup-db.sh` を実行したあとにバックエンドを再起動しなくてよい（ID は 1 から振り直される）

### metrics（JVM のヒープ・接続プール）

Actuator の `metrics` は、ふだんは公開していない（`application.yml` は `health` だけ）。性能テストのときだけ、環境変数 `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics` を付けて起動する。`health` 以外はログインが必要なので、テスト用ユーザーのアクセストークンを付けて見る。

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"perf00001@example.com","password":"password123"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -s -H "Authorization: Bearer $TOKEN" 'localhost:8080/api/metrics/jvm.memory.used?tag=area:heap'
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/metrics/hikaricp.connections.pending
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/metrics/jvm.gc.pause
```

- CI（`.github/workflows/ci.yml`）には入れない。将来、手で起動する GitHub Actions（`workflow_dispatch`）にする案もあるが、GitHub のサーバーは性能が一定せず比べにくいので、まずはローカルで動かす
- 動かすタイミング：
  - SQL・Mapper・インデックス（マイグレーション）を変えたとき
  - Spring Boot・PostgreSQL などの大きな更新のあと
  - AWS に apply・デプロイしたあと

## 9. 進め方

1つずつ Issue にして進める。

| 順 | 内容 |
|---|---|
| 1 | この計画書を書き、[テスト計画書](test-plan.md)の「対象外」を書き直す |
| 2 | テストデータの SQL（基準・10倍）と、専用の DB `sns_perf` を作る手順（Issue #125） |
| 3 | k6 のシナリオ（スモーク・負荷）、`perf/run.sh`、合格の基準（`thresholds`）、性能テストのときだけ metrics を公開する設定（Issue #127） |
| 4 | 初回の測定（ストレス・耐久・ログイン集中を含む）と、結果の記録（[結果](performance-test-results.md)）。遅い箇所は**改善ごとに別の Issue** にする（Issue #129） |
| 5 | AWS での測定（apply のあと） |
| 6 | ブラウザのパフォーマンステスト（「10」。Issue #142） |

- k6 のスクリプトとテストデータの SQL は、テストのための道具なので、[CLAUDE.md](../CLAUDE.md) の TDD の対象外とする
- 測定で見つかった問題を直すとき（SQL の書き換え・インデックスの追加など）は、TDD のルールに従う。動きを変えない改善でも、直す前に既存のテストが通っていることを確かめ、直したあとにもう一度測って、改善した数値を PR に書く

## 10. ブラウザのパフォーマンステスト

k6 は API の速さだけを測る。非機能要件の「操作から画面反映まで1秒以内」は、ブラウザでの描画を含めて確かめる必要があるので、Playwright で画面を操作して時間を測る（`frontend/browser-perf/`）。

### 測るもの

| 区分 | 測るもの | 目安（合否） |
|---|---|---|
| 最初の表示 | ログイン画面の FCP・LCP・DOMContentLoaded・HTML が届き終わるまで、送られた JavaScript・CSS の量 | LCP 2.5 秒以内（Web Vitals の「良い」）。ほかは記録だけ |
| 最初の表示 | 「ログイン」を押してからタイムラインが出るまで、読み込み直してからタイムラインが出るまで（再訪問。Cookie でトークンを再発行する） | 1 秒以内 |
| 最初の表示 | レイアウトのずれ（CLS）：ログイン画面、タイムライン（再訪問。画像・アイコンが出そろうまで） | 0.1 以下（Web Vitals の「良い」） |
| 操作から画面反映まで | タブの切り替え・続きの読み込み・投稿詳細・プロフィール・ユーザー検索・いいね・投稿 | 1 秒以内 |

- **時間の測り方**：テストの道具（Playwright）は画面を数十〜数百 ms おきに見に行くので、その待ちが時間に混ざらないよう、操作もその後の待ちもブラウザの中で行う。操作した瞬間から、結果が DOM にそろったフレーム（`requestAnimationFrame`）までを `performance.now()` で測る（`measure.ts` の `timeAction`）。操作の前からあった要素には印を付け、新しく描かれたものだけを数える（タブを切り替えたとき、古い一覧を「表示された」と数えないため）
- **画面は本番用にビルドする**（`vite build` → `vite preview`。ポートは 5173）。開発用のサーバーは最適化していないので遅く出る。`vite preview` も本番の CloudFront と同じく圧縮して配る
- **データ**：性能テストの DB `sns_perf` の基準の規模。ユーザーは、たくさんフォローしている perf00001（タイムラインが一番重い）
- **回数**：1 つの値を 5 回測って中央値と最大を出す（環境変数 `SAMPLES` で変えられる）。最初の 1 回は、サーバーとブラウザを温めるために捨てる
- **条件**：2 つの条件で測る

| 条件 | 内容 | 合否 |
|---|---|---|
| Mac のまま | 何も遅くしない | 付ける |
| 遅い端末・回線のまね | CPU を 4 倍遅く、回線は遅延 150ms・下り 1.6Mbps（Lighthouse のスマートフォンの条件と同じ。Chrome の DevTools Protocol で設定する） | 付けない（記録だけ。非機能要件は PC を優先しているため） |

- **CLS の数え方**（`measure.ts` の `clsScore`）：Web Vitals と同じ。`layout-shift` を記録し、クリック・キー入力の直後のずれ（操作の結果として予想できるもの）は除く。前のずれから 1 秒以内に続くずれを 1 つのまとまりとし（最初から 5 秒まで）、まとまりごとの合計の最大を取る。通信が落ち着いてから 0.5 秒待って数える。数え方は部品のテスト（`measure.test.ts`。Vitest で、CI でも動く）で確かめる
- タイムラインの CLS は、ログインのボタンを押したあとではなく、読み込み直したときに測る（押した直後のずれは「操作の結果」として数えないため）
- Chrome の回線のまねは、遅延を「届き始め」ではなく「届き終わり」に足すので、最初の1バイト（TTFB）ではなく「HTML が届き終わるまで」を記録する
- 画像の配信は測らない（LocalStack では本物の S3・CloudFront の速さが分からない。「3. 実行する環境」の段階 2 で測る）
- **CI では動かさない**（この計画書の冒頭の方針）。型チェックと、計算の部分の部品のテスト（CLS の数え方）だけ、CI で毎回行う

### 選ばなかったもの

- **Lighthouse**：ページを開いたときの値は詳しく出るが、ログインしたあとの操作（タブの切り替えなど）を測れない。Chrome を別に起動する仕組みも要る。読み込みの値（FCP・LCP）は Playwright でも取れるので、道具を増やさない

### 手順

```bash
# 1. データを入れる
perf/setup-db.sh base

# 2. バックエンドを、本番と同じ JVM の設定で sns_perf につないで起動する（backend で実行）
cd backend && ./gradlew bootJar
POSTGRES_DB=sns_perf java -Xms256m -Xmx512m -jar build/libs/backend-0.0.1-SNAPSHOT.jar

# 3. 別のターミナルで（frontend で実行）。ビルドと vite preview は自動で行う
cd frontend && npm run test:browser-perf    # 回数を変えるときは SAMPLES=10 npm run test:browser-perf
```

- 結果の表は、画面と `frontend/browser-perf-results/`（Git には入れない）に Markdown で出る。そのまま [結果](performance-test-results.md) に貼れる
- 測るたびに投稿・いいねが少し増える。前回と同じデータで比べるときは、`perf/setup-db.sh base` で入れ直す
