// アプリ用の CloudFront の、画面（S3）へのリクエストで動く関数（CloudFront Functions。viewer-request）。
// React の画面の URL（/users/yamada・/posts/1 など）は S3 にファイルがないので、index.html を返すように書き換える。
// URL の最後の部分に「.」があるもの（/favicon.svg・/assets/index-xxxx.js）はファイルとしてそのまま S3 から取る。
// ユーザー名などの画面の URL には「.」が入らない（ユーザー名は英数字と _ だけ）。
// /api/* は ALB へ送るので、この関数は通らない（cloudfront_app.tf）。
// CloudFront の「エラーのときに index.html を返す」設定は使わない（API の 404・403 まで画面に置き換わってしまうため）
function handler(event) {
  var request = event.request
  var last = request.uri.split('/').pop()
  if (last.indexOf('.') === -1) {
    request.uri = '/index.html'
  }
  return request
}
